/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

/**
 * The test and benchmark harness: a companion plugin that drives scenarios on a real server and
 * writes a machine-readable result the build can assert on.
 *
 * <p>It exists before any feature does, because a benchmark whose harness is not in the repository
 * is a benchmark nobody can check.
 */
@NullMarked
package in.gravaxis.trace.harness;

import org.jspecify.annotations.NullMarked;
