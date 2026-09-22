/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.capture;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps an event and its bounded payload together until the consumer acknowledges the handoff.
 * ADR-0017 requires atomic capture integration before listeners may use this M4 transport.
 * It has no overflow allocation or blocking producer fallback; rejected offers widen mapped loss bounds.
 */
public final class BoundedPayloadQueue implements AutoCloseable {
    public enum Offer {
        ACCEPTED(0),
        FULL(64),
        OVERSIZED(72),
        WRONG_PRODUCER(80),
        INVALID(88),
        EXHAUSTED(96);

        private final long counterOffset;

        Offer(long counterOffset) {
            this.counterOffset = counterOffset;
        }
    }

    public enum Read {
        EMPTY,
        READY,
        TOO_SMALL
    }

    private static final int MAGIC = 0x54525051;
    private static final int VERSION = 1;
    private static final long FROM = 32, TO = 40, LOSSES = 48, HEAD = 128, TAIL = 256, DATA = 4096;
    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle().withInvokeExactBehavior();
    private final Arena arena;
    private final MemorySegment memory;
    private final FileChannel channel;
    private final FileLock lock;
    private final int capacity;
    private final int maxPayload;
    private final long stride;
    private final AtomicLong producer = new AtomicLong();
    private final AtomicLong consumer = new AtomicLong();
    private long peeked = -1;

    private BoundedPayloadQueue(
            Arena arena,
            MemorySegment memory,
            FileChannel channel,
            FileLock lock,
            int capacity,
            int maxPayload,
            long stride) {
        this.arena = arena;
        this.memory = memory;
        this.channel = channel;
        this.lock = lock;
        this.capacity = capacity;
        this.maxPayload = maxPayload;
        this.stride = stride;
    }

    /** Blocking startup operation. Existing files are validated, never silently resized or reset. */
    public static BoundedPayloadQueue open(Path path, int capacity, int maxPayload) throws IOException {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1 || maxPayload < 1)
            throw new IllegalArgumentException("Positive power-of-two capacity and positive payload limit required");
        long stride = (56L + maxPayload + 7) & ~7L;
        long bytes = Math.addExact(DATA, Math.multiplyExact(stride, capacity));
        Files.createDirectories(path.toAbsolutePath().getParent());
        FileChannel channel;
        boolean fresh;
        try {
            channel = FileChannel.open(
                    path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
            fresh = true;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            fresh = false;
        }
        Arena arena = Arena.ofShared();
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Payload queue already owned");
            if (!fresh && channel.size() != bytes) throw new IOException("Payload queue length/configuration mismatch");
            MemorySegment memory = channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes, arena);
            if (fresh) {
                memory.fill((byte) 0);
                memory.set(ValueLayout.JAVA_INT, 0, MAGIC);
                memory.set(ValueLayout.JAVA_INT, 4, VERSION);
                memory.set(ValueLayout.JAVA_INT, 8, capacity);
                memory.set(ValueLayout.JAVA_INT, 12, maxPayload);
                memory.set(ValueLayout.JAVA_LONG, FROM, Long.MAX_VALUE);
                memory.set(ValueLayout.JAVA_LONG, TO, Long.MIN_VALUE);
                memory.force();
            } else if (memory.get(ValueLayout.JAVA_INT, 0) != MAGIC
                    || memory.get(ValueLayout.JAVA_INT, 4) != VERSION
                    || memory.get(ValueLayout.JAVA_INT, 8) != capacity
                    || memory.get(ValueLayout.JAVA_INT, 12) != maxPayload) {
                throw new IOException("Unrecognized payload queue header");
            }
            BoundedPayloadQueue queue =
                    new BoundedPayloadQueue(arena, memory, channel, lock, capacity, maxPayload, stride);
            long head = queue.get(HEAD), tail = queue.get(TAIL);
            if (head < 0 || tail < head || tail - head > capacity)
                throw new IOException("Corrupt payload queue cursors");
            return queue;
        } catch (IOException | RuntimeException e) {
            arena.close();
            channel.close();
            if (e instanceof OverlappingFileLockException) throw new IOException("Payload queue already mapped", e);
            throw e;
        }
    }

    /**
     * Producer only; copies before publication and returns immediately when admission fails.
     * The source bytes must remain stable during this call. Bounds describe the possibly lost capture.
     */
    public Offer offer(
            long position,
            long states,
            long actorTime,
            long metadata,
            int version,
            byte[] payload,
            int length,
            long fromMillis,
            long toMillis) {
        if (!owns(producer)) return reject(Offer.WRONG_PRODUCER, fromMillis, toMillis);
        if (length < 0 || length > payload.length || version < 0 || fromMillis > toMillis)
            return reject(Offer.INVALID, fromMillis, toMillis);
        if (length > maxPayload) return reject(Offer.OVERSIZED, fromMillis, toMillis);
        long tail = get(TAIL);
        if (tail == Long.MAX_VALUE) return reject(Offer.EXHAUSTED, fromMillis, toMillis);
        if (tail - get(HEAD) >= capacity) return reject(Offer.FULL, fromMillis, toMillis);
        long at = DATA + (tail & (capacity - 1L)) * stride;
        memory.set(ValueLayout.JAVA_LONG, at, position);
        memory.set(ValueLayout.JAVA_LONG, at + 8, states);
        memory.set(ValueLayout.JAVA_LONG, at + 16, actorTime);
        memory.set(ValueLayout.JAVA_LONG, at + 24, metadata);
        memory.set(ValueLayout.JAVA_INT, at + 32, version);
        memory.set(ValueLayout.JAVA_INT, at + 36, length);
        memory.set(ValueLayout.JAVA_LONG, at + 40, fromMillis);
        memory.set(ValueLayout.JAVA_LONG, at + 48, toMillis);
        for (int i = 0; i < length; i++) memory.set(ValueLayout.JAVA_BYTE, at + 56 + i, payload[i]);
        LONG.setRelease(memory, TAIL, tail + 1);
        return Offer.ACCEPTED;
    }

    /** Consumer-only copy. A failed handoff may retry; merely reading never frees capacity. */
    public Read peek(Buffer target) throws IOException {
        requireConsumer();
        peeked = -1;
        target.ticket = -1;
        target.length = 0;
        long head = get(HEAD);
        if (head == get(TAIL)) return Read.EMPTY;
        long at = DATA + (head & (capacity - 1L)) * stride;
        int length = memory.get(ValueLayout.JAVA_INT, at + 36);
        int version = memory.get(ValueLayout.JAVA_INT, at + 32);
        if (length < 0 || length > maxPayload || version < 0) throw new IOException("Malformed queued payload");
        if (target.payload.length < length) return Read.TOO_SMALL;
        for (int i = 0; i < 4; i++) target.words[i] = memory.get(ValueLayout.JAVA_LONG, at + i * 8L);
        for (int i = 0; i < length; i++) target.payload[i] = memory.get(ValueLayout.JAVA_BYTE, at + 56 + i);
        target.version = version;
        target.length = length;
        target.fromMillis = memory.get(ValueLayout.JAVA_LONG, at + 40);
        target.toMillis = memory.get(ValueLayout.JAVA_LONG, at + 48);
        target.ticket = head;
        peeked = head;
        return Read.READY;
    }

    /** Only the successfully peeked current ticket may release a slot; stale acknowledgements fail. */
    public boolean acknowledge(long ticket) {
        requireConsumer();
        if (ticket < 0 || ticket != peeked || ticket != get(HEAD)) return false;
        LONG.setRelease(memory, HEAD, ticket + 1);
        peeked = -1;
        return true;
    }

    private static boolean owns(AtomicLong owner) {
        long thread = Thread.currentThread().threadId();
        long current = owner.get();
        return current == thread || (current == 0 && owner.compareAndSet(0, thread));
    }

    private void requireConsumer() {
        if (!owns(consumer)) throw new IllegalStateException("Payload queue has another consumer");
    }

    private Offer reject(Offer reason, long from, long to) {
        long low = Math.min(from, to), high = Math.max(from, to), previous;
        do {
            previous = get(FROM);
        } while (low < previous && !(boolean) LONG.compareAndSet(memory, FROM, previous, low));
        do {
            previous = get(TO);
        } while (high > previous && !(boolean) LONG.compareAndSet(memory, TO, previous, high));
        // Recovery reads bounds even if process death happens before either counter increments.
        long ignored = (long) LONG.getAndAdd(memory, LOSSES, 1L);
        long offset = reason.counterOffset;
        ignored = (long) LONG.getAndAdd(memory, offset, 1L);
        return reason;
    }

    public long lossFromMillis() {
        return get(FROM);
    }

    public long lossToMillis() {
        return get(TO);
    }

    public long rejected() {
        return get(LOSSES);
    }

    public long rejected(Offer reason) {
        return reason == Offer.ACCEPTED ? 0 : get(reason.counterOffset);
    }

    public long pending() {
        return Math.max(0, get(TAIL) - get(HEAD));
    }

    /** Consumer-thread snapshot used before journal publication to conservatively bound other pending queues. */
    public long oldestPendingMillis() {
        long head = get(HEAD);
        return head == get(TAIL)
                ? Long.MAX_VALUE
                : memory.get(ValueLayout.JAVA_LONG, DATA + (head & (capacity - 1L)) * stride + 40);
    }

    private long get(long offset) {
        return (long) LONG.getAcquire(memory, offset);
    }

    /** Blocking shutdown operation; producer and consumer must already have stopped. */
    @Override
    public void close() throws IOException {
        try {
            memory.force();
        } finally {
            arena.close();
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    /** Reusable consumer-owned destination; only length bytes belong to the current read. */
    public static final class Buffer {
        private final long[] words = new long[4];
        private final byte[] payload;
        private long ticket = -1, fromMillis, toMillis;
        private int version, length;

        public Buffer(int capacity) {
            payload = new byte[capacity];
        }

        public long word(int index) {
            return words[index];
        }

        public byte[] payload() {
            return payload;
        }

        public long ticket() {
            return ticket;
        }

        public long fromMillis() {
            return fromMillis;
        }

        public long toMillis() {
            return toMillis;
        }

        public int version() {
            return version;
        }

        public int length() {
            return length;
        }
    }
}
