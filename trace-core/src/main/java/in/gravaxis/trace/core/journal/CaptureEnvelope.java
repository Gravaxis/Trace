/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** ADR-0017: exact bytes travel with their event; queue slots never become durable payload identities. */
public record CaptureEnvelope(long position, long states, long actorTime, long metadata, int version, byte[] payload) {
    public static final int MAX_PAYLOAD = 65536;
    private static final int HEADER = 48;

    public CaptureEnvelope {
        if (version < 0 || payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("Invalid capture payload");
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public byte[] encode() {
        return ByteBuffer.allocate(HEADER + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1)
                .putInt(version)
                .putLong(position)
                .putLong(states)
                .putLong(actorTime)
                .putLong(metadata)
                .putInt(payload.length)
                .putInt(0)
                .put(payload)
                .array();
    }

    public static CaptureEnvelope decode(byte[] bytes) throws IOException {
        if (bytes.length < HEADER || bytes.length > HEADER + MAX_PAYLOAD)
            throw new IOException("Invalid capture envelope length");
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != 1) throw new IOException("Unsupported capture envelope version");
        int version = b.getInt();
        long position = b.getLong(), states = b.getLong(), actorTime = b.getLong(), metadata = b.getLong();
        int length = b.getInt();
        if (version < 0 || length != bytes.length - HEADER || b.getInt() != 0)
            throw new IOException("Invalid capture envelope fields");
        byte[] payload = new byte[length];
        b.get(payload);
        return new CaptureEnvelope(position, states, actorTime, metadata, version, payload);
    }
}
