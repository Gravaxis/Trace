/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

/**
 * The contract every storage backend is held to, and the fixtures for exercising it.
 *
 * <p>Written once and subclassed per backend: a tier that behaves differently is a rollback that
 * behaves differently.
 */
@NullMarked
package in.gravaxis.trace.storage.testing;

import org.jspecify.annotations.NullMarked;
