/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

/**
 * The append-only journal every accepted event passes through before the store sees it.
 *
 * <p>See ADR-0013 for the format, the force policy and what a crash costs.
 */
@NullMarked
package in.gravaxis.trace.core.journal;

import org.jspecify.annotations.NullMarked;
