/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/**
 * A page of rows, held in reusable primitive arrays.
 *
 * <p>Columnar and reused on purpose. The memory-flatness guarantee (gate P2) says a rollback of
 * fifty million edits must not cost more heap than one of twenty-seven thousand, and the way to
 * fail that is to hand back a list of row objects. A cursor fills the same batch over and over;
 * nothing downstream may hold on to it.
 */
public final class MutationBatch {

    private final int capacity;

    private final long[] chunkKeys;
    private final long[] timestamps;
    private final int[] sequences;
    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final int[] beforeStates;
    private final int[] afterStates;
    private final int[] actors;
    private final byte[] causes;
    private final byte[] kinds;

    private int size;

    public MutationBatch(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.chunkKeys = new long[capacity];
        this.timestamps = new long[capacity];
        this.sequences = new int[capacity];
        this.xs = new int[capacity];
        this.ys = new int[capacity];
        this.zs = new int[capacity];
        this.beforeStates = new int[capacity];
        this.afterStates = new int[capacity];
        this.actors = new int[capacity];
        this.causes = new byte[capacity];
        this.kinds = new byte[capacity];
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return size;
    }

    public boolean isFull() {
        return size == capacity;
    }

    public void clear() {
        size = 0;
    }

    /**
     * Appends a row.
     *
     * @return the index it landed at
     * @throws IllegalStateException if the batch is full; callers check {@link #isFull()} first
     */
    public int add(
            long chunkKey,
            long timestamp,
            int sequence,
            int x,
            int y,
            int z,
            int beforeState,
            int afterState,
            int actorId,
            int cause,
            int kind) {
        if (size == capacity) {
            throw new IllegalStateException("Batch is full");
        }
        int index = size++;
        chunkKeys[index] = chunkKey;
        timestamps[index] = timestamp;
        sequences[index] = sequence;
        xs[index] = x;
        ys[index] = y;
        zs[index] = z;
        beforeStates[index] = beforeState;
        afterStates[index] = afterState;
        actors[index] = actorId;
        causes[index] = (byte) cause;
        kinds[index] = (byte) kind;
        return index;
    }

    public long chunkKey(int index) {
        return chunkKeys[index];
    }

    public long timestamp(int index) {
        return timestamps[index];
    }

    public int sequence(int index) {
        return sequences[index];
    }

    public int x(int index) {
        return xs[index];
    }

    public int y(int index) {
        return ys[index];
    }

    public int z(int index) {
        return zs[index];
    }

    public int beforeState(int index) {
        return beforeStates[index];
    }

    public int afterState(int index) {
        return afterStates[index];
    }

    public int actorId(int index) {
        return actors[index];
    }

    public int cause(int index) {
        return causes[index];
    }

    public int kind(int index) {
        return kinds[index];
    }
}
