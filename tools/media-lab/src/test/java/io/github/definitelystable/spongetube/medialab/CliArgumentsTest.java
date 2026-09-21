package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliArgumentsTest {

    @TempDir
    Path temp;

    @Test
    void parsesN0Defaults() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("fixtures"));
        Path trace = temp.resolve("trace.jsonl");

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + trace,
                "--session-id=test-1"
        });

        assertEquals(MediaLabProfile.N0, config.profile());
        assertEquals(0, config.dataPort());
        assertEquals(8, config.dataWorkers());
        assertEquals(0, config.controlPort());
        assertEquals(2, config.controlWorkers());
        assertEquals(8192, config.writeQuantumBytes());
        assertNull(config.referencePlaybackBitrateBps());
        assertNull(config.noProgressStartAfterMs());
        assertEquals(temp.resolve("trace.events.jsonl"), config.sessionTracePath());
        assertEquals(temp.resolve("trace.calibration.jsonl"), config.calibrationPath());
    }

    @Test
    void parsesN1ReferenceBitrate() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n1-fixtures"));

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + temp.resolve("n1.jsonl"),
                "--session-id=n1",
                "--profile=N1",
                "--reference-playback-bitrate-bps=2000000"
        });

        assertEquals(MediaLabProfile.N1, config.profile());
        assertEquals(2_000_000L, config.referencePlaybackBitrateBps());
        assertEquals(1_000_000L, config.resolvedScenario().aggregateRateBps());
    }

    @Test
    void parsesN4ExplicitStartOffset() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n4-fixtures"));

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + temp.resolve("n4.jsonl"),
                "--session-id=n4",
                "--profile=N4",
                "--no-progress-start-after-ms=15000"
        });

        assertEquals(MediaLabProfile.N4, config.profile());
        assertEquals(15_000L, config.noProgressStartAfterMs());
        assertEquals(
                120_000L,
                config.resolvedScenario().noProgressDurationMs());
    }

    @Test
    void parsesExplicitListenerConfiguration() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("explicit-fixtures"));

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + temp.resolve("explicit-trace.jsonl"),
                "--session-id=explicit-1",
                "--data-port=18081",
                "--data-workers=6",
                "--control-port=18082",
                "--control-workers=3",
                "--write-quantum-bytes=4096"
        });

        assertEquals(18081, config.dataPort());
        assertEquals(6, config.dataWorkers());
        assertEquals(18082, config.controlPort());
        assertEquals(3, config.controlWorkers());
        assertEquals(4096, config.writeQuantumBytes());
    }

    @Test
    void rejectsTraceInsideFixtureRoot() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("fixtures-trace"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + fixtureRoot.resolve("trace.jsonl"),
                        "--session-id=test-1"
                }));
    }

    @Test
    void rejectsSameExplicitDataAndControlPort() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("same-port-fixtures"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("same-port-trace.jsonl"),
                        "--session-id=test-1",
                        "--data-port=18081",
                        "--control-port=18081"
                }));
    }

    @Test
    void rejectsN1WithoutReferenceBitrate() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("missing-n1"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("missing-n1.jsonl"),
                        "--session-id=n1",
                        "--profile=N1"
                }));
    }

    @Test
    void rejectsN4WithoutExplicitStartOffset() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("missing-n4"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("missing-n4.jsonl"),
                        "--session-id=n4",
                        "--profile=N4"
                }));
    }
}
