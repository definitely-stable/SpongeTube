package io.github.definitelystable.spongetube.medialab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

record MediaLabConfig(
        Path fixtureRoot,
        Path tracePath,
        String sessionId,
        MediaLabProfile profile,
        int dataPort,
        int dataWorkers,
        int controlPort,
        int controlWorkers) {

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    MediaLabConfig {
        fixtureRoot = Objects.requireNonNull(fixtureRoot, "fixtureRoot").toAbsolutePath().normalize();
        tracePath = Objects.requireNonNull(tracePath, "tracePath").toAbsolutePath().normalize();
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        profile = Objects.requireNonNull(profile, "profile");

        if (!Files.isDirectory(fixtureRoot)) {
            throw new IllegalArgumentException("Fixture root is not a directory: " + fixtureRoot);
        }
        if (tracePath.startsWith(fixtureRoot)) {
            throw new IllegalArgumentException("Trace path must be outside the fixture root");
        }
        if (!SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("sessionId must match " + SESSION_ID.pattern());
        }

        validatePort(dataPort, "data-port");
        validatePort(controlPort, "control-port");
        validateWorkers(dataWorkers, "data-workers");
        validateWorkers(controlWorkers, "control-workers");

        if (dataPort != 0 && dataPort == controlPort) {
            throw new IllegalArgumentException("data-port and control-port must differ");
        }

        // B1 intentionally exposes only the control profile. B2 owns impairment behavior.
        if (profile != MediaLabProfile.N0) {
            throw new IllegalArgumentException(
                    "Profile " + profile + " is reserved for M0-B2; B1 supports N0 only");
        }
    }

    private static void validatePort(int value, String name) {
        if (value < 0 || value > 65535) {
            throw new IllegalArgumentException(name + " must be between 0 and 65535");
        }
    }

    private static void validateWorkers(int value, String name) {
        if (value < 1 || value > 128) {
            throw new IllegalArgumentException(name + " must be between 1 and 128");
        }
    }
}
