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
        assertEquals(0, config.port());
        assertEquals(8, config.workers());
    }

    @Test
    void rejectsFutureImpairmentProfilesInB1() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("fixtures"));

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
