/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/**
 * A storage failure, with a reason an operator can act on.
 *
 * <p>"Database is busy" is the error message this project exists to do better than. Every failure
 * carries a {@link Reason} so the message can say what happened and what to do about it.
 */
public class StoreException extends Exception {

    private static final long serialVersionUID = 1L;

    /** What went wrong, in terms an operator can act on. */
    public enum Reason {
        /** The data directory is held by another server. One writer per directory. */
        LOCKED,
        /** A shard failed verification and was quarantined. */
        CORRUPT,
        /** The disk is full or unwritable. */
        DISK,
        /** A write conflicted with another writer inside this process. */
        CONTENDED,
        /** The backend rejected a statement: a bug in Trace, not in the operator's setup. */
        INTERNAL
    }

    private final Reason reason;

    public StoreException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public StoreException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
