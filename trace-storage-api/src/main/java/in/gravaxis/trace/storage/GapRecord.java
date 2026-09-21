/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/**
 * A window of time during which events were lost.
 *
 * <p>The bounds are deliberately generous: a gap may only ever be widened, never narrowed, so that
 * a refusal is conservative. Losing history is bad; quietly rolling back a world using history that
 * has a hole in it is worse.
 *
 * @param fromMillis start of the window, inclusive
 * @param toMillis end of the window, inclusive
 * @param reason why events were lost
 * @param droppedCount how many records are known to be missing, or -1 when unknown
 * @param detail free text for the operator, such as which producer slot overflowed
 */
public record GapRecord(long fromMillis, long toMillis, Reason reason, long droppedCount, String detail) {

    /**
     * Why events are missing.
     *
     * <p>Ids are explicit and persisted. Relying on declaration order would mean that inserting a
     * reason one day silently relabels every gap already on disk.
     */
    public enum Reason {
        /** The ring and its spill were both full: back-pressure beat the consumer. */
        OVERFLOW(1),
        /** The server died and whatever had not been journalled could not be recovered. */
        CRASH_WINDOW(2),
        /** A shard failed verification and was quarantined, so its window is no longer readable. */
        QUARANTINE(3),
        /** Capture was switched off, or the plugin was not loaded. */
        NOT_CAPTURED(4),
        /** History was deliberately expired by retention. */
        EXPIRED(5),
        /** History was deliberately purged. */
        PURGED(6);

        private final int id;

        Reason(int id) {
            this.id = id;
        }

        /** The persisted id. Stable forever. */
        public int id() {
            return id;
        }

        /** The reason for a persisted id. */
        public static Reason byId(int id) {
            for (Reason reason : values()) {
                if (reason.id == id) {
                    return reason;
                }
            }
            throw new IllegalArgumentException("Unknown gap reason id: " + id);
        }
    }

    public GapRecord {
        if (fromMillis > toMillis) {
            throw new IllegalArgumentException("Inverted gap: " + fromMillis + " > " + toMillis);
        }
    }

    public boolean overlaps(long windowStart, long windowEnd) {
        return fromMillis <= windowEnd && toMillis >= windowStart;
    }
}
