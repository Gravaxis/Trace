/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import in.gravaxis.trace.core.record.EventRecords;

/**
 * Records on their way into a store: the same four-long encoding the ring and the journal use.
 *
 * <p>Nothing is unpacked between the tick thread and the store. The words travel as they were
 * written, and only the store decides which of them become columns.
 */
public final class RecordBatch {

    private final long[] words;
    private int count;

    public RecordBatch(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.words = new long[capacity * EventRecords.LONGS];
    }

    /** Wraps an existing buffer; {@code count} records of four longs each. */
    public static RecordBatch wrapping(long[] words, int count) {
        RecordBatch batch = new RecordBatch(Math.max(1, count));
        System.arraycopy(words, 0, batch.words, 0, count * EventRecords.LONGS);
        batch.count = count;
        return batch;
    }

    public int capacity() {
        return words.length / EventRecords.LONGS;
    }

    public int count() {
        return count;
    }

    public boolean isFull() {
        return count == capacity();
    }

    public void clear() {
        count = 0;
    }

    /** Appends one record. */
    public void add(long position, long states, long actorTime, long metadata) {
        if (isFull()) {
            throw new IllegalStateException("Batch is full");
        }
        int base = count * EventRecords.LONGS;
        words[base] = position;
        words[base + 1] = states;
        words[base + 2] = actorTime;
        words[base + 3] = metadata;
        count++;
    }

    public long position(int index) {
        return words[index * EventRecords.LONGS];
    }

    public long states(int index) {
        return words[index * EventRecords.LONGS + 1];
    }

    public long actorTime(int index) {
        return words[index * EventRecords.LONGS + 2];
    }

    public long metadata(int index) {
        return words[index * EventRecords.LONGS + 3];
    }
}
