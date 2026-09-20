/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/**
 * Where a scan has reached: the key of the last row it returned.
 *
 * <p>This is the whole of keyset pagination. Resuming means asking for rows beyond this key, which
 * a clustered store answers with a seek — unlike an offset, which makes it count everything it has
 * already returned, and unlike a snapshot, which it would have to keep somewhere.
 *
 * @param chunkKey Morton key of the last row returned
 * @param timestamp its timestamp, in epoch milliseconds
 * @param sequence its intra-millisecond sequence
 */
public record CursorPosition(long chunkKey, long timestamp, int sequence) {

    /** Compares two positions in the primary-key order the shards are clustered by. */
    public int compareTo(CursorPosition other) {
        int byChunk = Long.compareUnsigned(chunkKey, other.chunkKey);
        if (byChunk != 0) {
            return byChunk;
        }
        int byTime = Long.compare(timestamp, other.timestamp);
        return byTime != 0 ? byTime : Integer.compare(sequence, other.sequence);
    }
}
