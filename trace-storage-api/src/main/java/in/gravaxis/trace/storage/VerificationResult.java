/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/** Distinguishes checked content from legacy shards that have no trusted digest. */
public record VerificationResult(int verified, int unverified, int quarantined, int blobsVerified, int blobsCorrupt) {}
