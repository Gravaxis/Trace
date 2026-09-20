/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import org.jspecify.annotations.Nullable;

/**
 * A streaming read over stored mutations.
 *
 * <p>The contract that matters, and the reason the whole storage layer is shaped this way: a cursor
 * never materialises its result. It fills a caller-owned {@link MutationBatch}, the caller consumes
 * it, and the memory cost of a scan is one batch regardless of how many rows match. Implementations
 * page with a keyset — the last key they returned — never with an offset, and they never count the
 * matching rows before returning them.
 *
 * <p>When the section-patch format arrives, patches are expanded behind this same interface, so the
 * rollback engine above never learns which of the two encodings a row came from.
 */
public interface MutationCursor extends AutoCloseable {

    /**
     * Fills {@code batch} with the next page of rows.
     *
     * @param batch cleared and filled by this call
     * @return true if the batch has rows; false when the scan is finished
     * @throws StoreException if the backend fails
     */
    boolean next(MutationBatch batch) throws StoreException;

    /**
     * Where the scan has reached, so it can be resumed after a restart.
     *
     * @return the position after the last row returned, or null before the first page
     */
    @Nullable
    CursorPosition position();

    @Override
    void close() throws StoreException;
}
