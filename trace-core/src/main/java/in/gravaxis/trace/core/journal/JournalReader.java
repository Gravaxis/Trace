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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Reads the journal back, and decides where it ends.
 *
 * <p>The end of the log is the first frame that fails either of the two integrity rules in
 * {@link JournalFrames}. Everything up to that point is replayed; everything after it never
 * happened, which is exactly the guarantee a crash needs: a torn tail costs the records inside it
 * and nothing else.
 */
public final class JournalReader {

    /** What a replay does with each frame. */
    @FunctionalInterface
    public interface FrameHandler {

        /**
         * Handles one frame.
         *
         * @param lsn position the frame was written at
         * @param type one of the {@code TYPE_} constants
         * @param words payload interpreted as longs; for events, four per record
         * @param count records in the frame
         * @param minCapture earliest capture timestamp in the frame
         * @param maxCapture latest capture timestamp in the frame
         */
        void frame(long lsn, int type, long[] words, int count, long minCapture, long maxCapture) throws IOException;

        /** Old consumers must refuse payload frames rather than silently lose their trailing bytes. */
        default void captured(long lsn, CaptureEnvelope envelope, long minCapture, long maxCapture) throws IOException {
            throw new IOException("Consumer does not support captured payload frames");
        }
    }

    private JournalReader() {}

    /**
     * Replays every valid frame at or after {@code fromLsn}.
     *
     * @return a summary of where the log ended and how it ended
     */
    public static ReplaySummary replay(Path directory, long fromLsn, FrameHandler handler) throws IOException {
        if (!Files.isDirectory(directory)) {
            return new ReplaySummary(fromLsn, 0, 0, false, 0, 0, 0);
        }
        List<Path> segments = segmentsOf(directory);
        long endLsn = fromLsn;
        long frames = 0;
        long records = 0;
        boolean cleanClose = false;
        long lastMaxCapture = 0;
        Long lastSalt = null;
        int runs = 0;
        long lastLowWatermark = 0;

        for (Path segment : segments) {
            long base = JournalWriter.segmentBaseOf(segment);
            try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ)) {
                ByteBuffer header =
                        ByteBuffer.allocate(JournalFrames.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                long offset = 0;
                long size = channel.size();
                while (offset + JournalFrames.HEADER_BYTES <= size) {
                    header.clear();
                    readFully(channel, header, offset);
                    header.flip();

                    long lsn = base + offset;
                    if (header.getInt(JournalFrames.OFFSET_MAGIC) != JournalFrames.MAGIC) {
                        break;
                    }
                    if (header.getLong(JournalFrames.OFFSET_LSN) != lsn) {
                        // A frame that thinks it lives somewhere else is a leftover, not a record.
                        break;
                    }
                    long salt = header.getLong(JournalFrames.OFFSET_SALT);
                    if (lastSalt == null || salt != lastSalt) {
                        // A new run picked the log up and appended to it. That is a restart
                        // boundary, not the end of the log: the frame is ours if it is intact and
                        // in the right place, which rules one and two have already decided.
                        runs++;
                    }
                    lastSalt = salt;
                    int payloadBytes = header.getInt(JournalFrames.OFFSET_PAYLOAD_LENGTH);
                    if (payloadBytes < 0 || offset + JournalFrames.HEADER_BYTES + payloadBytes > size) {
                        break;
                    }
                    ByteBuffer payload = ByteBuffer.allocate(payloadBytes).order(ByteOrder.LITTLE_ENDIAN);
                    readFully(channel, payload, offset + JournalFrames.HEADER_BYTES);
                    payload.flip();
                    if (!checksumMatches(header, payload)) {
                        break;
                    }

                    int type = header.getShort(JournalFrames.OFFSET_TYPE);
                    int count = header.getInt(JournalFrames.OFFSET_COUNT);
                    validateSemantics(type, count, payload);
                    long minCapture = header.getLong(JournalFrames.OFFSET_MIN_CAPTURE);
                    long maxCapture = header.getLong(JournalFrames.OFFSET_MAX_CAPTURE);
                    if (lsn >= fromLsn) {
                        if (type == JournalFrames.TYPE_CAPTURED) {
                            handler.captured(lsn, CaptureEnvelope.decode(payload.array()), minCapture, maxCapture);
                        } else {
                            long[] words = new long[payloadBytes / Long.BYTES];
                            payload.asLongBuffer().get(words);
                            handler.frame(lsn, type, words, count, minCapture, maxCapture);
                        }
                        frames++;
                        records += type == JournalFrames.TYPE_EVENTS || type == JournalFrames.TYPE_CAPTURED ? count : 0;
                    }
                    cleanClose = type == JournalFrames.TYPE_CLEAN_CLOSE;
                    lastMaxCapture = Math.max(lastMaxCapture, maxCapture);
                    long mark = header.getLong(JournalFrames.OFFSET_LOW_WATERMARK);
                    if (mark > 0) {
                        lastLowWatermark = mark;
                    }
                    offset += JournalFrames.HEADER_BYTES + JournalFrames.align(payloadBytes);
                    endLsn = base + offset;
                }
            }
        }
        return new ReplaySummary(endLsn, frames, records, cleanClose, lastMaxCapture, runs, lastLowWatermark);
    }

    /** Where a segment's valid frames end, used when reopening the journal for appending. */
    static long endOfSegment(Path segment, long base) throws IOException {
        long[] end = {base};
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(JournalFrames.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            long offset = 0;
            long size = channel.size();
            while (offset + JournalFrames.HEADER_BYTES <= size) {
                header.clear();
                readFully(channel, header, offset);
                header.flip();
                if (header.getInt(JournalFrames.OFFSET_MAGIC) != JournalFrames.MAGIC
                        || header.getLong(JournalFrames.OFFSET_LSN) != base + offset) {
                    break;
                }
                // The salt is read but does not end the scan: see replay(). Stopping here on a
                // change would truncate every frame a previous run appended to this segment, which
                // is exactly what it used to do.
                int payloadBytes = header.getInt(JournalFrames.OFFSET_PAYLOAD_LENGTH);
                if (payloadBytes < 0 || offset + JournalFrames.HEADER_BYTES + payloadBytes > size) {
                    break;
                }
                ByteBuffer payload = ByteBuffer.allocate(payloadBytes).order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, payload, offset + JournalFrames.HEADER_BYTES);
                payload.flip();
                if (!checksumMatches(header, payload)) {
                    break;
                }
                validateSemantics(
                        header.getShort(JournalFrames.OFFSET_TYPE), header.getInt(JournalFrames.OFFSET_COUNT), payload);
                offset += JournalFrames.HEADER_BYTES + JournalFrames.align(payloadBytes);
                end[0] = base + offset;
            }
        }
        return end[0];
    }

    private static void validateSemantics(int type, int count, ByteBuffer payload) throws IOException {
        switch (type) {
            case JournalFrames.TYPE_CAPTURED -> {
                if (count != 1) throw new IOException("Invalid capture envelope count");
                byte[] exact = new byte[payload.remaining()];
                payload.duplicate().get(exact);
                CaptureEnvelope.decode(exact);
            }
            case JournalFrames.TYPE_EVENTS -> {
                if (count < 0 || (long) count * 32 != payload.remaining())
                    throw new IOException("Invalid event frame length");
            }
            case JournalFrames.TYPE_GAP -> {
                if (count != 1 || payload.remaining() != 32) throw new IOException("Invalid gap frame");
            }
            case JournalFrames.TYPE_CLEAN_CLOSE -> {
                if (count != 0 || payload.hasRemaining()) throw new IOException("Invalid clean-close frame");
            }
            default -> throw new IOException("Unsupported journal frame type " + type);
        }
    }

    private static boolean checksumMatches(ByteBuffer header, ByteBuffer payload) {
        CRC32C crc = new CRC32C();
        ByteBuffer checksummed = header.duplicate();
        checksummed.position(0).limit(JournalFrames.CHECKSUMMED_HEADER_BYTES);
        crc.update(checksummed);
        crc.update(payload.duplicate());
        return (int) crc.getValue() == header.getInt(JournalFrames.OFFSET_CRC);
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                break;
            }
            at += read;
        }
    }

    private static List<Path> segmentsOf(Path directory) throws IOException {
        List<Path> segments = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(JournalWriter::isSegment).forEach(segments::add);
        }
        segments.sort(Comparator.comparingLong(JournalWriter::segmentBaseOf));
        return segments;
    }

    /**
     * What a replay found.
     *
     * @param runs how many runs wrote the frames that were read, counted by salt changes; more
     *     than one simply means the server was restarted with this segment still current
     * @param lastLowWatermarkMillis the oldest capture time the last frame said might still be
     *     un-journalled, which is the lower bound a crash gap has to start at
     * @param endLsn where the valid log ends; appending continues here
     * @param frames frames replayed
     * @param records event records replayed
     * @param cleanClose whether the last frame said the server shut down in an orderly way
     * @param lastCaptureMillis the newest capture timestamp seen, for bounding a crash gap
     */
    public record ReplaySummary(
            long endLsn,
            long frames,
            long records,
            boolean cleanClose,
            long lastCaptureMillis,
            int runs,
            long lastLowWatermarkMillis) {}
}
