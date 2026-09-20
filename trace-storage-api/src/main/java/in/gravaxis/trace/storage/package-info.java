/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

/**
 * The storage SPI: what a backend must implement, and the streaming contract above it.
 *
 * <p>Every backend — the embedded default, and the external tiers a large network would use — takes
 * the same {@link in.gravaxis.trace.storage.ScanPlan} and feeds the same rollback engine. That is
 * what keeps one query planner in the project rather than one per backend.
 */
@NullMarked
package in.gravaxis.trace.storage;

import org.jspecify.annotations.NullMarked;
