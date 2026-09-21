/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.scenarios;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;

/**
 * A real TCP peer using the pinned server's codecs rather than remembered packet ids.
 * This tests natural server handlers, not independent wire compatibility (M4 capture plan).
 * No world or player object crosses into its reader thread.
 */
final class LoopbackClient implements AutoCloseable {
    private static final String PROTOCOL = "net.minecraft.network.protocol.";
    private static final int MAX_FRAME = 8 * 1024 * 1024;
    private final Map<String, Map<Object, Integer>> outbound = new HashMap<>();
    private final Map<String, Map<Integer, String>> inbound = new HashMap<>();
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Thread reader;
    private volatile String phase = "login";
    private volatile int compression = -1;
    private volatile int acknowledged;
    private volatile int teleports;
    private volatile boolean closed;
    private volatile @Nullable Throwable failure;
    private final Object progress = new Object();

    LoopbackClient(int port, String name) throws Exception {
        for (String state : List.of("handshake", "login", "configuration", "game")) {
            String owner = PROTOCOL + state + "."
                    + switch (state) {
                        case "handshake" -> "HandshakeProtocols";
                        case "login" -> "LoginProtocols";
                        case "configuration" -> "ConfigurationProtocols";
                        default -> "GameProtocols";
                    };
            inventory(state, owner, "SERVERBOUND_TEMPLATE", true);
            if (!state.equals("handshake")) inventory(state, owner, "CLIENTBOUND_TEMPLATE", false);
        }
        socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(30_000);
        socket.setTcpNoDelay(true);
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());
        try {
            int version = (int) type("net.minecraft.SharedConstants")
                    .getMethod("getProtocolVersion")
                    .invoke(null);
            send(
                    "handshake",
                    create(
                            PROTOCOL + "handshake.ClientIntentionPacket",
                            version,
                            "127.0.0.1",
                            port,
                            constant(PROTOCOL + "handshake.ClientIntent", "LOGIN")));
            send(
                    "login",
                    create(
                            PROTOCOL + "login.ServerboundHelloPacket",
                            name,
                            UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        } catch (Exception e) {
            socket.close();
            throw e;
        }
        reader = Thread.ofPlatform().name("trace-loopback-client").start(this::readLoop);
    }

    private void inventory(String state, String owner, String field, boolean serverbound) throws Exception {
        Object template = constant(owner, field);
        Object details = type("net.minecraft.network.ProtocolInfo$DetailsProvider")
                .getMethod("details")
                .invoke(template);
        Map<Object, Integer> outgoing = new HashMap<>();
        Map<Integer, String> incoming = new HashMap<>();
        Class<?> visitor = type("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
        Object proxy = Proxy.newProxyInstance(visitor.getClassLoader(), new Class<?>[] {visitor}, (p, m, a) -> {
            if (!m.getName().equals("accept") || a == null)
                throw new IllegalStateException("Unexpected packet visitor call");
            Object packetType = a[0];
            int id = (int) a[1];
            outgoing.put(packetType, id);
            incoming.put(
                    id,
                    String.valueOf(type(PROTOCOL + "PacketType").getMethod("id").invoke(packetType)));
            return null;
        });
        type("net.minecraft.network.ProtocolInfo$Details")
                .getMethod("listPackets", visitor)
                .invoke(details, proxy);
        if (serverbound) outbound.put(state, outgoing);
        else inbound.put(state, incoming);
    }

    void awaitReady() throws Exception {
        await(() -> phase.equals("game") && teleports > 0);
    }

    int teleports() {
        return teleports;
    }

    void awaitTeleportAfter(int previous) throws Exception {
        await(() -> teleports > previous);
    }

    void awaitAcknowledged(int sequence) throws Exception {
        await(() -> acknowledged >= sequence);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        synchronized (progress) {
            while (!condition.getAsBoolean()) {
                if (failure != null) throw new IOException("Protocol client failed in " + phase, failure);
                if (closed || System.nanoTime() >= deadline)
                    throw new IOException("Protocol barrier timed out in " + phase);
                progress.wait(100);
            }
        }
    }

    void breakBlock(int x, int y, int z, int sequence) throws Exception {
        send(
                "game",
                create(
                        PROTOCOL + "game.ServerboundPlayerActionPacket",
                        constant(PROTOCOL + "game.ServerboundPlayerActionPacket$Action", "START_DESTROY_BLOCK"),
                        create("net.minecraft.core.BlockPos", x, y, z),
                        constant("net.minecraft.core.Direction", "UP"),
                        sequence));
        send("game", constant(PROTOCOL + "game.ServerboundClientTickEndPacket", "INSTANCE"));
    }

    void placeBlock(int x, int y, int z, int sequence) throws Exception {
        Object hit = create(
                "net.minecraft.world.phys.BlockHitResult",
                create("net.minecraft.world.phys.Vec3", x + 0.5, (double) y, z + 0.5),
                constant("net.minecraft.core.Direction", "UP"),
                create("net.minecraft.core.BlockPos", x, y - 1, z),
                false);
        send(
                "game",
                create(
                        PROTOCOL + "game.ServerboundUseItemOnPacket",
                        constant("net.minecraft.world.InteractionHand", "MAIN_HAND"),
                        hit,
                        sequence));
        send("game", constant(PROTOCOL + "game.ServerboundClientTickEndPacket", "INSTANCE"));
    }

    private void readLoop() {
        try {
            while (!closed) {
                byte[] framed = input.readNBytes(readVarInt(input));
                byte[] payload = framed;
                if (compression >= 0) {
                    var compressed = new DataInputStream(new ByteArrayInputStream(framed));
                    int expanded = readVarInt(compressed);
                    if (expanded == 0) payload = compressed.readAllBytes();
                    else {
                        payload = new InflaterInputStream(compressed).readNBytes(MAX_FRAME + 1);
                        if (payload.length != expanded || payload.length > MAX_FRAME)
                            throw new IOException("Invalid compressed packet");
                    }
                }
                var packet = new DataInputStream(new ByteArrayInputStream(payload));
                int id = readVarInt(packet);
                Map<Integer, String> packetNames = inbound.get(phase);
                if (packetNames == null) throw new IOException("Unknown protocol phase");
                String name = packetNames.get(id);
                if (name == null) throw new IOException("Unknown packet id " + id + " in " + phase);
                receive(name.substring(name.indexOf(':') + 1), packet.readAllBytes());
                synchronized (progress) {
                    progress.notifyAll();
                }
            }
        } catch (Exception e) {
            if (!closed) failure = e;
            synchronized (progress) {
                progress.notifyAll();
            }
        }
    }

    private void receive(String name, byte[] body) throws Exception {
        switch (name) {
            case "login_compression" ->
                compression = (int)
                        member(decode("login.ClientboundLoginCompressionPacket", body), "getCompressionThreshold");
            case "login_finished" -> {
                send("login", constant(PROTOCOL + "login.ServerboundLoginAcknowledgedPacket", "INSTANCE"));
                phase = "configuration";
            }
            case "select_known_packs" ->
                send("configuration", create(PROTOCOL + "configuration.ServerboundSelectKnownPacks", List.of()));
            case "code_of_conduct" ->
                send(
                        "configuration",
                        constant(PROTOCOL + "configuration.ServerboundAcceptCodeOfConductPacket", "INSTANCE"));
            case "finish_configuration" -> {
                send(
                        "configuration",
                        constant(PROTOCOL + "configuration.ServerboundFinishConfigurationPacket", "INSTANCE"));
                phase = "game";
                send("game", create(PROTOCOL + "game.ServerboundPlayerLoadedPacket"));
            }
            case "keep_alive" ->
                send(
                        phase,
                        create(
                                PROTOCOL + "common.ServerboundKeepAlivePacket",
                                member(decode("common.ClientboundKeepAlivePacket", body), "getId")));
            case "ping" ->
                send(
                        phase,
                        create(
                                PROTOCOL + "common.ServerboundPongPacket",
                                member(decode("common.ClientboundPingPacket", body), "getId")));
            case "player_position" -> {
                Object position = decode("game.ClientboundPlayerPositionPacket", body);
                send("game", create(PROTOCOL + "game.ServerboundAcceptTeleportationPacket", member(position, "id")));
                teleports++;
            }
            case "block_changed_ack" ->
                acknowledged = (int) member(decode("game.ClientboundBlockChangedAckPacket", body), "sequence");
            case "chunk_batch_finished" ->
                send("game", create(PROTOCOL + "game.ServerboundChunkBatchReceivedPacket", 1.0f));
            case "disconnect", "login_disconnect" ->
                throw new IOException("Server disconnected protocol client in " + phase);
            default -> {
                /* World rendering and registry consumption are outside this action-only client's scope. */
            }
        }
    }

    private synchronized void send(String state, Object packet) throws Exception {
        Object packetType = member(packet, "type");
        Map<Object, Integer> packetIds = outbound.get(state);
        if (packetIds == null) throw new IOException("Unknown protocol phase");
        Integer id = packetIds.get(packetType);
        if (id == null) throw new IOException("Packet not registered in " + state + ": " + packetType);
        var bytes = new ByteArrayOutputStream();
        var data = new DataOutputStream(bytes);
        writeVarInt(data, id);
        data.write(encode(packet));
        byte[] body = bytes.toByteArray();
        if (compression >= 0) {
            bytes.reset();
            if (body.length >= compression) {
                writeVarInt(data, body.length);
                try (var deflater = new DeflaterOutputStream(bytes)) {
                    deflater.write(body);
                }
            } else {
                writeVarInt(data, 0);
                data.write(body);
            }
            body = bytes.toByteArray();
        }
        writeVarInt(output, body.length);
        output.write(body);
        output.flush();
    }

    private static Object buffer(byte[] bytes) throws Exception {
        Object raw = type("io.netty.buffer.Unpooled")
                .getMethod("wrappedBuffer", byte[].class)
                .invoke(null, (Object) bytes);
        return type("net.minecraft.network.FriendlyByteBuf")
                .getConstructor(type("io.netty.buffer.ByteBuf"))
                .newInstance(raw);
    }

    private static byte[] encode(Object packet) throws Exception {
        Object raw = type("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null);
        Object buf = type("net.minecraft.network.FriendlyByteBuf")
                .getConstructor(type("io.netty.buffer.ByteBuf"))
                .newInstance(raw);
        try {
            Object codec = packet.getClass().getField("STREAM_CODEC").get(null);
            type("net.minecraft.network.codec.StreamEncoder")
                    .getMethod("encode", Object.class, Object.class)
                    .invoke(codec, buf, packet);
            byte[] bytes = new byte
                    [(int) type("io.netty.buffer.ByteBuf")
                            .getMethod("readableBytes")
                            .invoke(buf)];
            type("io.netty.buffer.ByteBuf").getMethod("readBytes", byte[].class).invoke(buf, (Object) bytes);
            return bytes;
        } finally {
            type("io.netty.buffer.ByteBuf").getMethod("release").invoke(buf);
        }
    }

    private static Object decode(String packetClass, byte[] bytes) throws Exception {
        Object buf = buffer(bytes);
        try {
            return type("net.minecraft.network.codec.StreamDecoder")
                    .getMethod("decode", Object.class)
                    .invoke(constant(PROTOCOL + packetClass, "STREAM_CODEC"), buf);
        } finally {
            type("io.netty.buffer.ByteBuf").getMethod("release").invoke(buf);
        }
    }

    static Object create(String name, Object... args) throws Exception {
        for (var constructor : type(name).getConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                if (p.isPrimitive())
                    p = switch (p.getName()) {
                        case "int" -> Integer.class;
                        case "long" -> Long.class;
                        case "float" -> Float.class;
                        case "double" -> Double.class;
                        case "boolean" -> Boolean.class;
                        default -> throw new IllegalStateException("Unsupported primitive");
                    };
                matches &= p.isInstance(args[i]);
            }
            if (matches) return constructor.newInstance(args);
        }
        throw new NoSuchMethodException("No matching constructor: " + name);
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private static Object constant(String owner, String field) throws Exception {
        return type(owner).getField(field).get(null);
    }

    private static Object member(Object object, String name) throws Exception {
        return object.getClass().getMethod(name).invoke(object);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = in.readUnsignedByte();
            value |= (next & 127) << shift;
            if ((next & 128) == 0) {
                if (value < 0 || value > MAX_FRAME) throw new IOException("Packet integer exceeds client bound");
                return value;
            }
        }
        throw new IOException("Overlong packet integer");
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        do {
            int next = value & 127;
            value >>>= 7;
            out.writeByte(value == 0 ? next : next | 128);
        } while (value != 0);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        socket.close();
        try {
            reader.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (reader.isAlive()) throw new IOException("Client reader did not stop");
    }
}
