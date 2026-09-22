/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import in.gravaxis.trace.core.capture.BoundedPayloadQueue;
import in.gravaxis.trace.core.journal.CaptureEnvelope;
import in.gravaxis.trace.core.journal.JournalFrames;
import in.gravaxis.trace.core.journal.JournalReader;
import in.gravaxis.trace.core.journal.JournalWriter;
import java.io.IOException;
import java.util.Arrays;

/** ADR-0017 handoff retains the retry source until the journal and atomic store publication succeed. */
public final class PayloadHandoff {
    private final BoundedPayloadQueue queue;
    private final BoundedPayloadQueue.Buffer buffer = new BoundedPayloadQueue.Buffer(CaptureEnvelope.MAX_PAYLOAD);
    private long reportedFrom = Long.MAX_VALUE, reportedTo = Long.MIN_VALUE, reportedCount;

    public PayloadHandoff(BoundedPayloadQueue queue) {
        this.queue = queue;
    }

    /** Blocking consumer operation. Failure must stop all later appends to the same applied watermark. */
    public int drain(JournalWriter journal, EventStore store, long oldestStaged, int limit)
            throws IOException, StoreException {
        recordLoss(store);
        int count = 0;
        while (count < limit) {
            var branch = queue.peek(buffer);
            if (branch == BoundedPayloadQueue.Read.EMPTY) break;
            if (branch != BoundedPayloadQueue.Read.READY) throw new IOException("Payload exceeds handoff buffer");
            var envelope = new CaptureEnvelope(
                    buffer.word(0),
                    buffer.word(1),
                    buffer.word(2),
                    buffer.word(3),
                    buffer.version(),
                    Arrays.copyOf(buffer.payload(), buffer.length()));
            long lsn = journal.appendCaptured(
                    envelope, buffer.fromMillis(), buffer.toMillis(), Math.min(buffer.fromMillis(), oldestStaged));
            journal.force();
            store.appendCaptured(envelope, lsn);
            if (!queue.acknowledge(buffer.ticket())) throw new IOException("Payload acknowledgement lost ownership");
            count++;
        }
        recordLoss(store);
        return count;
    }

    /** Bounds, not count alone, are authoritative if a producer dies during rejection. */
    public void recordLoss(EventStore store) throws StoreException {
        long count = queue.rejected(), from = queue.lossFromMillis(), to = queue.lossToMillis();
        if (from == Long.MAX_VALUE && to == Long.MIN_VALUE) return;
        // A partly written pair cannot justify a narrow interval.
        if (from == Long.MAX_VALUE || to == Long.MIN_VALUE) {
            from = Long.MIN_VALUE;
            to = Long.MAX_VALUE;
        }
        if (from == reportedFrom && to == reportedTo && count == reportedCount) return;
        store.recordGap(new GapRecord(
                from,
                to,
                GapRecord.Reason.OVERFLOW,
                count == 0 ? -1 : count,
                "bounded payload queue rejection; persistent conservative window"));
        reportedFrom = from;
        reportedTo = to;
        reportedCount = count;
    }

    /** Exact-byte replay shared by runtime and crash gates. Unsupported frames refuse. */
    public static JournalReader.FrameHandler replayInto(EventStore store) {
        return new JournalReader.FrameHandler() {
            @Override
            public void captured(long lsn, CaptureEnvelope envelope, long min, long max) throws IOException {
                try {
                    store.appendCaptured(envelope, lsn);
                } catch (StoreException e) {
                    throw new IOException("Captured replay failed at " + lsn, e);
                }
            }

            @Override
            public void frame(long lsn, int type, long[] words, int count, long min, long max) throws IOException {
                try {
                    if (type == JournalFrames.TYPE_EVENTS && count > 0)
                        store.append(RecordBatch.wrapping(words, count), lsn);
                    else if (type == JournalFrames.TYPE_GAP) {
                        int reason = Math.toIntExact(words[2]);
                        store.recordGap(new GapRecord(
                                words[0], words[1], GapRecord.Reason.byId(reason), words[3], "journal replay"));
                    }
                } catch (StoreException e) {
                    throw new IOException("Journal replay failed at " + lsn, e);
                }
            }
        };
    }
}
