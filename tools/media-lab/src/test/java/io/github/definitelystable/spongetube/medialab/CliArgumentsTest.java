package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliArgumentsTest {

    @TempDir
    Path temp;

    @Test
    void parsesB1Defaults() throws Exception {
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
                "--control-workers=3"
        });

        assertEquals(18081, config.dataPort());
        assertEquals(6, config.dataWorkers());
        assertEquals(18082, config.controlPort());
        assertEquals(3, config.controlWorkers());
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
    void rejectsFutureImpairmentProfilesInB1() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("future-fixtures"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("trace.jsonl"),
                        "--session-id=test-1",
                        "--profile=N1"
                }));
    }
}
