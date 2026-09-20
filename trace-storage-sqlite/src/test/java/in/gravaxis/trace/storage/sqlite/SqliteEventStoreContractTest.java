/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import in.gravaxis.trace.storage.EventStore;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.testing.EventStoreContract;
import java.nio.file.Path;

/** The embedded default, held to the shared storage contract. */
class SqliteEventStoreContractTest extends EventStoreContract {

    @Override
    protected EventStore open(Path directory) throws StoreException {
        return SqliteEventStore.open(directory);
    }
}
