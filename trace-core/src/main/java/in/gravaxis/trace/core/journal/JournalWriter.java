/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.journal;

import in.gravaxis.trace.core.record.EventRecords;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;
import org.jspecify.annotations.Nullable;

/**
 * Writes events to the append-only journal, and forces them within a bounded window.
 *
 * <p>Frames precede store publication so recovery can replay uncertain commits (ADR-0013).
 * Payload handoffs force before acknowledgement (ADR-0023). The named process-kill gates do not
 * establish power-loss or arbitrary-instruction crash guarantees.
 *
 * <p>Single-writer by construction: the consumer thread owns it.
 */
public final class JournalWriter implements AutoCloseable {

    /** Passed as the floor when the caller has no applied watermark to respect. */
    public static final long NO_FLOOR = -1L;

    private static final String SEGMENT_PREFIX = "j-";
    private static final String SEGMENT_SUFFIX = ".tjl";

    private final Path directory;
    private final long segmentBytes;
    private final long salt;

    private FileChannel channel;
    private long segmentBase;
    private long position;
    private long forcedLsn;
    private boolean failed;

    @FunctionalInterface
    interface WriteProbe {
        void afterHeader() throws IOException;
    }

    private WriteProbe writeProbe = () -> {};

    void writeProbe(WriteProbe probe) {
        writeProbe = probe;
    }

    private final ByteBuffer header =
            ByteBuffer.allocateDirect(JournalFrames.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private ByteBuffer payload = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.LITTLE_ENDIAN);

    private JournalWriter(
            Path directory, long segmentBytes, long salt, FileChannel channel, long segmentBase, long position) {
        this.directory = directory;
        this.segmentBytes = segmentBytes;
        this.salt = salt;
        this.channel = channel;
        this.segmentBase = segmentBase;
        this.position = position;
        this.forcedLsn = segmentBase + position;
    }

    /**
     * Opens the journal for appending, continuing after whatever the last valid frame was.
     *
     * @param directory where segments live
     * @param segmentBytes roll to a new segment past this size
     * @param salt identifies this run's frames; a fresh value each time the journal is opened
     */
    public static JournalWriter open(Path directory, long segmentBytes, long salt) throws IOException {
        return open(directory, segmentBytes, salt, NO_FLOOR);
    }

    /**
     * Opens the journal for appending at a position strictly above {@code floorLsn}.
     *
     * <p>The floor is the store's applied watermark. The store discards any frame at or below it, so
     * a journal that resumed below the floor would have every new frame silently thrown away —
     * captured, journalled, counted, and gone. If the log does not already reach past the floor, a
     * fresh segment is started above it instead, which keeps positions monotonic even if the journal
     * was truncated, restored from a backup, or replaced by hand.
     *
     * @param floorLsn a position the journal must start after, or {@link #NO_FLOOR}
     */
    public static JournalWriter open(Path directory, long segmentBytes, long salt, long floorLsn) throws IOException {
        Files.createDirectories(directory);
        JournalScan scan = JournalScan.scan(directory);
        long base;
        long offset;
        Path segment;
        if (scan.lastSegment() == null) {
            base = 0;
            offset = 0;
            segment = directory.resolve(segmentName(0));
        } else {
            base = scan.lastSegmentBase();
            offset = scan.endLsn() - base;
            segment = scan.lastSegment();
        }
        if (base + offset <= floorLsn) {
            base = JournalFrames.align(floorLsn + 1);
            offset = 0;
            segment = directory.resolve(segmentName(base));
        }
        FileChannel channel =
                FileChannel.open(segment, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        channel.position(offset);
        // Anything past the last valid frame is unreadable by definition, so it is truncated rather
        // than left for a future scan to trip over.
        channel.truncate(offset);
        return new JournalWriter(directory, segmentBytes, salt, channel, base, offset);
    }

    /** The position the next frame will be written at. */
    public long nextLsn() {
        return segmentBase + position;
    }

    /** The highest position that has been forced to disk. */
    public long forcedLsn() {
        return forcedLsn;
    }

    /**
     * Appends a frame of event records.
     *
     * @param words four longs per record, as captured
     * @param count how many records
     * @param minCapture earliest capture timestamp in the frame
     * @param maxCapture latest capture timestamp in the frame
     * @param lowWatermark the oldest capture timestamp not yet journalled anywhere, used to bound a
     *     crash gap; a gap that started after an event it must cover would be worse than no gap
     * @return the position this frame was written at
     */
    public long appendEvents(long[] words, int count, long minCapture, long maxCapture, long lowWatermark)
            throws IOException {
        int payloadBytes = count * EventRecords.BYTES;
        ensurePayloadCapacity(payloadBytes);
        payload.clear();
        for (int i = 0; i < count * EventRecords.LONGS; i++) {
            payload.putLong(words[i]);
        }
        payload.flip();
        return writeFrame(JournalFrames.TYPE_EVENTS, count, minCapture, maxCapture, lowWatermark, payload);
    }

    /** Appends a gap marker: the record that makes a later rollback refuse rather than guess. */
    public long appendGap(long fromMillis, long toMillis, int reasonId, long dropped) throws IOException {
        payload.clear();
        payload.putLong(fromMillis);
        payload.putLong(toMillis);
        payload.putLong(reasonId);
        payload.putLong(dropped);
        payload.flip();
        return writeFrame(JournalFrames.TYPE_GAP, 1, fromMillis, toMillis, fromMillis, payload);
    }

    /** Blocking consumer operation; the envelope contains exact event and payload bytes. */
    public long appendCaptured(CaptureEnvelope envelope, long from, long to, long lowWatermark) throws IOException {
        return writeFrame(JournalFrames.TYPE_CAPTURED, 1, from, to, lowWatermark, ByteBuffer.wrap(envelope.encode()));
    }

    /** Records that the server shut down in an orderly way, so a restart knows it did. */
    public long appendCleanClose(long nowMillis) throws IOException {
        payload.clear();
        payload.flip();
        return writeFrame(JournalFrames.TYPE_CLEAN_CLOSE, 0, nowMillis, nowMillis, nowMillis, payload);
    }

    private long writeFrame(int type, int count, long minCapture, long maxCapture, long lowWatermark, ByteBuffer body)
            throws IOException {
        if (failed) throw new IOException("Journal requires reopen after failed write");
        try {
            return writeFrameUnchecked(type, count, minCapture, maxCapture, lowWatermark, body);
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    private long writeFrameUnchecked(
            int type, int count, long minCapture, long maxCapture, long lowWatermark, ByteBuffer body)
            throws IOException {
        int payloadBytes = body.remaining();
        int framed = JournalFrames.HEADER_BYTES + JournalFrames.align(payloadBytes);
        if (position > 0 && position + framed > segmentBytes) {
            rollSegment();
        }
        long lsn = segmentBase + position;

        header.clear();
        header.putInt(JournalFrames.MAGIC);
        header.putShort((short) type);
        header.putShort((short) 0);
        header.putInt(payloadBytes);
        header.putInt(count);
        header.putLong(lsn);
        header.putLong(minCapture);
        header.putLong(maxCapture);
        header.putLong(lowWatermark);
        header.putLong(salt);
        header.putInt(0);
        header.putInt(0);
        header.flip();

        CRC32C crc = new CRC32C();
        ByteBuffer checksummed = header.duplicate();
        checksummed.limit(JournalFrames.CHECKSUMMED_HEADER_BYTES);
        crc.update(checksummed);
        ByteBuffer bodyForCrc = body.duplicate();
        crc.update(bodyForCrc);
        header.putInt(JournalFrames.OFFSET_CRC, (int) crc.getValue());

        writeFully(header);
        writeProbe.afterHeader();
        writeFully(body);
        int padding = JournalFrames.align(payloadBytes) - payloadBytes;
        if (padding > 0) {
            writeFully(ByteBuffer.allocate(padding));
        }
        position += framed;
        return lsn;
    }

    private void writeFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private void ensurePayloadCapacity(int bytes) {
        if (payload.capacity() < bytes) {
            payload =
                    ByteBuffer.allocateDirect(Integer.highestOneBit(bytes) * 2).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private void rollSegment() throws IOException {
        channel.force(true);
        channel.close();
        segmentBase += position;
        position = 0;
        channel = FileChannel.open(
                directory.resolve(segmentName(segmentBase)),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ);
        channel.truncate(0);
    }

    /** Flushes everything written so far to the device. */
    public void force() throws IOException {
        if (failed) throw new IOException("Journal requires reopen after failed write");
        try {
            channel.force(false);
        } catch (IOException e) {
            failed = true;
            throw e;
        }
        forcedLsn = segmentBase + position;
    }

    @Override
    public void close() throws IOException {
        try {
            if (!failed) force();
        } finally {
            channel.close();
        }
    }

    static String segmentName(long base) {
        return SEGMENT_PREFIX + "%016x".formatted(base) + SEGMENT_SUFFIX;
    }

    static boolean isSegment(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(SEGMENT_PREFIX) && name.endsWith(SEGMENT_SUFFIX);
    }

    static long segmentBaseOf(Path path) {
        String name = path.getFileName().toString();
        return Long.parseUnsignedLong(
                name.substring(SEGMENT_PREFIX.length(), name.length() - SEGMENT_SUFFIX.length()), 16);
    }

    /** Where a scan of the existing segments ended. */
    record JournalScan(@Nullable Path lastSegment, long lastSegmentBase, long endLsn) {

        static JournalScan scan(Path directory) throws IOException {
            JournalReader.replay(directory, 0, new JournalReader.FrameHandler() {
                @Override
                public void frame(long lsn, int type, long[] words, int count, long min, long max) {}

                @Override
                public void captured(long lsn, CaptureEnvelope envelope, long min, long max) {}
            });
            Path last = null;
            long lastBase = 0;
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(JournalWriter::isSegment).toList()) {
                    long base = segmentBaseOf(file);
                    if (last == null || base > lastBase) {
                        last = file;
                        lastBase = base;
                    }
                }
            }
            if (last == null) {
                return new JournalScan(null, 0, 0);
            }
            long end = JournalReader.endOfSegment(last, lastBase);
            return new JournalScan(last, lastBase, end);
        }
    }
}
