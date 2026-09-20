/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

/**
 * The Paper plugin itself: the only part of Trace that may touch Bukkit freely.
 *
 * <p>The engine below it ({@code trace-core}) has no server dependency, which is what lets the
 * planner and the codecs be property-tested without booting Minecraft.
 */
@NullMarked
package in.gravaxis.trace;

import org.jspecify.annotations.NullMarked;
