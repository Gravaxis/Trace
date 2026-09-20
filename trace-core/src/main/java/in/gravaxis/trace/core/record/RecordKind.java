/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.record;

import org.jspecify.annotations.Nullable;

/**
 * What a record describes.
 *
 * <p>Four bits in the record, so at most sixteen kinds ever. The ones defined here are what M2
 * produces; containers, entities and the history-only kinds arrive with the milestone that captures
 * them. Ids are persisted and never reused.
 */
public enum RecordKind {
    /** A block changed. */
    BLOCK(1),

    /**
     * Events were dropped. A rollback whose window touches one of these refuses rather than
     * producing a partly-correct world.
     */
    GAP(14),

    /** Liveness marker written by the consumer; carries no world change. */
    HEARTBEAT(15);

    private final int id;

    RecordKind(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** The kind for a persisted id, or null when this build does not know it. */
    public static @Nullable RecordKind byId(int id) {
        for (RecordKind kind : values()) {
            if (kind.id == id) {
                return kind;
            }
        }
        return null;
    }
}
