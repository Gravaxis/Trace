/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.journal;

/**
 * The journal's on-disk framing.
 *
 * <p>Two rules decide where the log ends, and both exist because of a way a journal that
 * looks correct is not (ADR-0013):
 *
 * <ol>
 *   <li>the checksum covers the <em>whole</em> header as well as the payload, so a bit flip in a
 *       routing field cannot send records to the wrong window undetected;
 *   <li>a frame records the position it was written at, and a frame read from anywhere else is not
 *       a frame;
 * </ol>
 * Salt changes identify restarts; they never invalidate an otherwise intact frame.
 */
public final class JournalFrames {

    /** {@code TRJ1} in ASCII. */
    public static final int MAGIC = 0x54524A31;

    /** Header size in bytes; payloads follow immediately and are eight-byte aligned. */
    public static final int HEADER_BYTES = 64;

    /** Records of captured events. */
    public static final int TYPE_EVENTS = 1;

    /** One event and its exact versioned payload, atomically applied by the store. */
    public static final int TYPE_CAPTURED = 7;

    /** A window of lost events. */
    public static final int TYPE_GAP = 4;

    /** Written on an orderly shutdown; its absence is how a restart knows it was not one. */
    public static final int TYPE_CLEAN_CLOSE = 6;

    // Header layout, in bytes from the start of the frame.
    static final int OFFSET_MAGIC = 0;
    static final int OFFSET_TYPE = 4;
    static final int OFFSET_FLAGS = 6;
    static final int OFFSET_PAYLOAD_LENGTH = 8;
    static final int OFFSET_COUNT = 12;
    static final int OFFSET_LSN = 16;
    static final int OFFSET_MIN_CAPTURE = 24;
    static final int OFFSET_MAX_CAPTURE = 32;
    static final int OFFSET_LOW_WATERMARK = 40;
    static final int OFFSET_SALT = 48;
    // Reserved sits before the checksum on purpose: the checksummed region is then one contiguous
    // run ending at the checksum itself, so no header byte can be changed without being noticed.
    // A property test flips a bit at every offset in a frame and requires the replay to stop.
    static final int OFFSET_RESERVED = 56;
    static final int OFFSET_CRC = 60;

    /** Bytes of the header the checksum covers: everything before the checksum field itself. */
    static final int CHECKSUMMED_HEADER_BYTES = OFFSET_CRC;

    private JournalFrames() {}

    /** Rounds a payload length up to the eight-byte alignment frames are written at. */
    public static int align(int length) {
        return (length + 7) & ~7;
    }

    /** Rounds a journal position up to the same alignment, for choosing a segment base. */
    public static long align(long position) {
        return (position + 7) & ~7L;
    }
}
