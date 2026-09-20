/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.journal;

import static org.assertj.core.api.Assertions.assertThat;

import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.core.testing.Properties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The journal's job is to still be readable after something went wrong, so most of these tests are
 * about things going wrong: a torn tail, a flipped bit, and the leftovers of a previous run.
 */
class JournalRoundTripTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("every record written is replayed, in order")
    void replaysWhatItWrote() throws IOException {
        List<long[]> written = new ArrayList<>();
        try (JournalWriter writer = JournalWriter.open(directory, 1 << 20, 1234L)) {
            for (int frame = 0; frame < 5; frame++) {
                long[] words = records(frame, 10);
                written.add(words);
                writer.appendEvents(words, 10, frame, frame + 1, frame);
            }
            writer.force();
        }

        List<long[]> replayed = new ArrayList<>();
        JournalReader.ReplaySummary summary =
                JournalReader.replay(directory, 0, (lsn, type, words, count, min, max) -> {
                    if (type == JournalFrames.TYPE_EVENTS) {
                        replayed.add(words);
                    }
                });

        assertThat(summary.records()).isEqualTo(50);
        assertThat(summary.frames()).isEqualTo(5);
        assertThat(replayed).hasSize(5);
        for (int i = 0; i < written.size(); i++) {
            assertThat(replayed.get(i)).containsExactly(written.get(i));
        }
    }

    @Test
    @DisplayName("replay resumes from a position, so an applied prefix is not applied twice")
    void replaysFromAPosition() throws IOException {
        long secondFrame;
        try (JournalWriter writer = JournalWriter.open(directory, 1 << 20, 1L)) {
            writer.appendEvents(records(0, 4), 4, 0, 1, 0);
            secondFrame = writer.appendEvents(records(1, 4), 4, 1, 2, 1);
            writer.force();
        }

        JournalReader.ReplaySummary summary =
                JournalReader.replay(directory, secondFrame, (lsn, type, words, count, min, max) -> {});

        assertThat(summary.records()).isEqualTo(4);
    }

    @Test
    @DisplayName("a torn tail costs the torn frame and nothing before it")
    void tornTailStopsCleanly() throws IOException {
        try (JournalWriter writer = JournalWriter.open(directory, 1 << 20, 7L)) {
            writer.appendEvents(records(0, 8), 8, 0, 1, 0);
            writer.appendEvents(records(1, 8), 8, 1, 2, 1);
            writer.force();
        }
        Path segment = onlySegment();
        long size = Files.size(segment);
        // A kill in the middle of the second frame's payload.
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.truncate(size - 24);
        }

        JournalReader.ReplaySummary summary =
                JournalReader.replay(directory, 0, (lsn, type, words, count, min, max) -> {});

        assertThat(summary.records())
                .as("the intact frame survives, the torn one does not")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("a flipped bit anywhere in a frame stops the replay there")
    void corruptionStopsTheReplay() {
        Properties.forAll("a flipped bit is caught", 80, gen -> {
            try {
                Path caseDirectory = Files.createTempDirectory("journal-corruption");
                try (JournalWriter writer = JournalWriter.open(caseDirectory, 1 << 20, 99L)) {
                    writer.appendEvents(records(0, 4), 4, 10, 20, 10);
                    writer.appendEvents(records(1, 4), 4, 20, 30, 20);
                    writer.force();
                }
                Path segment;
                try (var files = Files.list(caseDirectory)) {
                    segment = files.findFirst().orElseThrow();
                }
                long size = Files.size(segment);
                long firstFrameBytes = JournalFrames.HEADER_BYTES + 4L * EventRecords.BYTES;
                // Corrupt one bit of the second frame, header or payload alike.
                long at = firstFrameBytes + gen.longs(0, size - firstFrameBytes - 1);
                flipBit(segment, at, (int) gen.choice(8));

                JournalReader.ReplaySummary summary =
                        JournalReader.replay(caseDirectory, 0, (lsn, type, words, count, min, max) -> {});

                assertThat(summary.records())
                        .as("a damaged frame is never handed to the store")
                        .isEqualTo(4);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    @DisplayName("frames left over from a previous run are not replayed as live data")
    void staleFramesAreRejected() throws IOException {
        try (JournalWriter first = JournalWriter.open(directory, 1 << 20, 111L)) {
            first.appendEvents(records(0, 16), 16, 0, 1, 0);
            first.appendEvents(records(1, 16), 16, 1, 2, 1);
            first.force();
        }
        Path segment = onlySegment();
        byte[] previousRun = Files.readAllBytes(segment);

        // The segment is recycled: rewound, and rewritten with a shorter frame under a new salt.
        Files.write(segment, new byte[0]);
        try (JournalWriter second = JournalWriter.open(directory, 1 << 20, 222L)) {
            second.appendEvents(records(2, 2), 2, 5, 6, 5);
            second.force();
        }
        // The previous run's bytes are still physically there, behind the new frame.
        long newLength = Files.size(segment);
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.position(newLength);
            channel.write(ByteBuffer.wrap(previousRun, (int) newLength, previousRun.length - (int) newLength));
        }

        JournalReader.ReplaySummary summary =
                JournalReader.replay(directory, 0, (lsn, type, words, count, min, max) -> {});

        assertThat(summary.records())
                .as("stale frames have valid magic and valid checksums; only the salt gives them away")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an orderly shutdown is distinguishable from a crash")
    void cleanCloseIsRecorded() throws IOException {
        try (JournalWriter writer = JournalWriter.open(directory, 1 << 20, 5L)) {
            writer.appendEvents(records(0, 2), 2, 0, 1, 0);
            writer.appendCleanClose(42);
        }

        assertThat(JournalReader.replay(directory, 0, (lsn, type, words, count, min, max) -> {})
                        .cleanClose())
                .isTrue();
    }

    @Test
    @DisplayName("reopening the journal appends after the last valid frame")
    void reopeningContinues() throws IOException {
        try (JournalWriter writer = JournalWriter.open(directory, 1 << 20, 1L)) {
            writer.appendEvents(records(0, 3), 3, 0, 1, 0);
            writer.force();
        }
        try (JournalWriter writer = JournalWriter.open(directory, 1 << 20, 2L)) {
            writer.appendEvents(records(1, 3), 3, 1, 2, 1);
            writer.force();
        }

        // The salt changes per run, so the second run's frames end the replay at their own tail —
        // which is why a reopen truncates to the last valid frame rather than appending blindly.
        JournalReader.ReplaySummary summary =
                JournalReader.replay(directory, 0, (lsn, type, words, count, min, max) -> {});
        assertThat(summary.records()).isEqualTo(3);
    }

    private Path onlySegment() throws IOException {
        try (var files = Files.list(directory)) {
            return files.findFirst().orElseThrow();
        }
    }

    private static void flipBit(Path file, long at, int bit) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(one, at);
            byte value = (byte) (one.get(0) ^ (1 << bit));
            channel.write(ByteBuffer.wrap(new byte[] {value}), at);
        }
    }

    private static long[] records(int frame, int count) {
        long[] words = new long[count * EventRecords.LONGS];
        for (int i = 0; i < count; i++) {
            words[i * 4] = EventRecords.packPosition(frame * 100 + i, 64, i);
            words[i * 4 + 1] = EventRecords.packStates(i, i + 1, 0);
            words[i * 4 + 2] = EventRecords.packActorTime(1000 + i, 7);
            words[i * 4 + 3] = EventRecords.packMetadata(i, 1, 1, 1, 7, 0);
        }
        return words;
    }
}
