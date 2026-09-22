/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.dictionary;

import in.gravaxis.trace.storage.StoreException;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded admission keeps tick threads away from persistence. A failed head stays pending until
 * its identity is durable; unpublished identities can only cause capture loss, never dangling ids.
 */
public final class DictionaryUpdates {
    public static final int CAPACITY = 128;
    private final ActorDictionary actors;
    private final WorldDictionary worlds;
    private final int capacity;
    private final ConcurrentLinkedQueue<Update> requests = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();

    public DictionaryUpdates(ActorDictionary actors, WorldDictionary worlds) {
        this(actors, worlds, CAPACITY);
    }

    DictionaryUpdates(ActorDictionary actors, WorldDictionary worlds, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive queue capacity required");
        this.actors = actors;
        this.worlds = worlds;
        this.capacity = capacity;
    }

    public boolean actor(UUID uuid, String name) {
        if (!ActorDictionary.validName(name)) {
            rejected.incrementAndGet();
            return false;
        }
        if (!reserve()) return false;
        requests.add(new Update(uuid, name, true));
        accepted.incrementAndGet();
        return true;
    }

    public boolean world(UUID uuid) {
        if (worlds.idOf(uuid) >= 0) return true;
        if (!reserve()) return false;
        requests.add(new Update(uuid, "", false));
        accepted.incrementAndGet();
        return true;
    }

    private boolean reserve() {
        int size;
        do {
            size = pending.get();
            if (size == capacity) {
                rejected.incrementAndGet();
                return false;
            }
        } while (!pending.compareAndSet(size, size + 1));
        return true;
    }

    /** Blocking single-consumer operation. Peek/persist/ack makes persistence failure retryable. */
    public int drain(int limit) throws StoreException {
        int count = 0;
        while (count < limit) {
            Update update = requests.peek();
            if (update == null) break;
            if (update.actor) actors.register(update.uuid, update.name);
            else worlds.register(update.uuid);
            requests.remove();
            pending.decrementAndGet();
            completed.incrementAndGet();
            count++;
        }
        return count;
    }

    /** Blocking worker flush; a reserved but not yet published request cannot be declared complete. */
    public void flush() throws StoreException {
        drain(capacity);
        if (pending() != 0)
            throw new StoreException(StoreException.Reason.CONTENDED, "Dictionary registration still pending");
    }

    public int pending() {
        return pending.get();
    }

    public long accepted() {
        return accepted.get();
    }

    public long rejected() {
        return rejected.get();
    }

    public long completed() {
        return completed.get();
    }

    private record Update(UUID uuid, String name, boolean actor) {}
}
