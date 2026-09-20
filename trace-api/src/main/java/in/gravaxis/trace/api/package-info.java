/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The public Trace API.
 *
 * <p>This package is the only supported integration surface. It is published as
 * {@code in.gravaxis:trace-api} under the Apache License 2.0, and it never exposes Trace internals:
 * an implementation change is not an API change.
 *
 * <p>The API is deliberately small right now. Lookup, logging and rollback entry points are added
 * in the milestone that first implements them, so that nothing here is designed speculatively.
 */
@NullMarked
package in.gravaxis.trace.api;

import org.jspecify.annotations.NullMarked;
