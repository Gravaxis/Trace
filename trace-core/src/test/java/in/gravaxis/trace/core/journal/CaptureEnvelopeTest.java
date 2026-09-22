/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.journal;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureEnvelopeTest {
    @TempDir
    Path directory;

    @Test
    void exactOddBytesLegacyAndRestartSaltReplayTogether() throws Exception {
        var e = new CaptureEnvelope(1, 2, 3, 4, 7, new byte[] {0, -1, 3});
        try (var w = JournalWriter.open(directory, 4096, 1)) {
            w.appendEvents(new long[] {5, 6, 7, 8}, 1, 1, 2, 1);
        }
        try (var w = JournalWriter.open(directory, 4096, 2)) {
            w.appendCaptured(e, 3, 4, 1);
        }
        var seen = new ArrayList<CaptureEnvelope>();
        int[] legacy = {0};
        var summary = JournalReader.replay(directory, 0, new JournalReader.FrameHandler() {
            @Override
            public void frame(long lsn, int type, long[] words, int count, long min, long max) {
                assertThat(type).isEqualTo(JournalFrames.TYPE_EVENTS);
                assertThat(words).containsExactly(5, 6, 7, 8);
                assertThat(count).isEqualTo(1);
                legacy[0]++;
            }

            @Override
            public void captured(long lsn, CaptureEnvelope envelope, long min, long max) {
                assertThat(min).isEqualTo(3);
                assertThat(max).isEqualTo(4);
                seen.add(envelope);
            }
        });
        assertThat(legacy[0]).isEqualTo(1);
        assertThat(summary.records()).isEqualTo(2);
        assertThat(summary.runs()).isEqualTo(2);
        assertThat(seen)
                .singleElement()
                .satisfies(actual -> assertThat(actual.encode()).containsExactly(e.encode()));
        assertThatThrownBy(() -> JournalReader.replay(directory, 0, (lsn, type, words, count, min, max) -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not support");
    }

    @Test
    void validChecksumUnknownEnvelopeRefusesReopenWithoutTruncation() throws Exception {
        try (var w = JournalWriter.open(directory, 4096, 1)) {
            w.appendCaptured(new CaptureEnvelope(1, 2, 3, 4, 1, new byte[] {9}), 1, 2, 1);
        }
        Path file = directory.resolve(JournalWriter.segmentName(0));
        byte[] bytes = Files.readAllBytes(file);
        var view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        view.putInt(64, 99);
        var crc = new CRC32C();
        crc.update(bytes, 0, 60);
        crc.update(bytes, 64, view.getInt(8));
        view.putInt(60, (int) crc.getValue());
        Files.write(file, bytes);
        assertThatThrownBy(() -> JournalWriter.open(directory, 4096, 2))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported capture envelope");
        assertThat(Files.readAllBytes(file)).containsExactly(bytes);
    }

    @Test
    void partialJournalWritePoisonsWriterUntilReopen() throws Exception {
        var e = new CaptureEnvelope(1, 2, 3, 4, 1, new byte[] {9});
        try (var w = JournalWriter.open(directory, 4096, 1)) {
            w.writeProbe(() -> {
                throw new IOException("injected after header");
            });
            assertThatThrownBy(() -> w.appendCaptured(e, 1, 2, 1)).hasMessage("injected after header");
            assertThatThrownBy(() -> w.appendCaptured(e, 1, 2, 1)).hasMessageContaining("requires reopen");
            assertThatThrownBy(w::force).hasMessageContaining("requires reopen");
        }
        assertThat(JournalReader.replay(directory, 0, (a, b, c, d, f, g) -> {}).frames())
                .isZero();
        try (var w = JournalWriter.open(directory, 4096, 2)) {
            assertThat(w.nextLsn()).isZero();
            w.appendCaptured(e, 1, 2, 1);
            w.force();
        }
        int[] count = {0};
        JournalReader.replay(directory, 0, new JournalReader.FrameHandler() {
            @Override
            public void frame(long a, int b, long[] c, int d, long f, long g) {
                fail("Wrong frame branch");
            }

            @Override
            public void captured(long lsn, CaptureEnvelope actual, long from, long to) {
                assertThat(actual.encode()).containsExactly(e.encode());
                count[0]++;
            }
        });
        assertThat(count[0]).isEqualTo(1);
    }
}
