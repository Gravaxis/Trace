/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

/**
 * Turning events into records, without a server.
 *
 * <p>This lives in {@code trace-core} rather than beside the Bukkit listener for one reason: it is
 * the allocation-critical path, and a gate that measures a stand-in is not a gate. Here the
 * benchmark and the exact allocation test call the same class the listener calls.
 */
@NullMarked
package in.gravaxis.trace.core.capture;

import org.jspecify.annotations.NullMarked;
