/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

/**
 * Tier S: the embedded, zero-setup default. Time-sharded SQLite, clustered by chunk.
 *
 * <p>Runs on the sqlite-jdbc the server already provides; see ADR-0003 for why that is not a choice
 * Trace gets to make.
 */
@NullMarked
package in.gravaxis.trace.storage.sqlite;

import org.jspecify.annotations.NullMarked;
