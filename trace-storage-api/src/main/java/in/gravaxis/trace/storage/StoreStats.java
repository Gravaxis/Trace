/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/**
 * What {@code /trace status} needs to answer "is the store healthy" without opening a file.
 *
 * @param hotRows rows in the unsealed window
 * @param sealedRows rows in sealed shards
 * @param shardCount number of sealed shards
 * @param bytesOnDisk total size of the store
 * @param gapCount recorded gaps
 * @param appliedLsn journal position the store has consumed
 */
public record StoreStats(
        long hotRows, long sealedRows, int shardCount, long bytesOnDisk, int gapCount, long appliedLsn) {}
