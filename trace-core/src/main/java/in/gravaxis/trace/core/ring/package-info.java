/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

/**
 * The transport between the threads that capture events and the one that stores them.
 *
 * <p>One ring per producer thread, memory-mapped so its contents outlive the process. See ADR-0012.
 */
@NullMarked
package in.gravaxis.trace.core.ring;

import org.jspecify.annotations.NullMarked;
