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
        int port,
        int workers) {

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    MediaLabConfig {
        fixtureRoot = Objects.requireNonNull(fixtureRoot, "fixtureRoot").toAbsolutePath().normalize();
        tracePath = Objects.requireNonNull(tracePath, "tracePath").toAbsolutePath().normalize();
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        profile = Objects.requireNonNull(profile, "profile");

        if (!Files.isDirectory(fixtureRoot)) {
            throw new IllegalArgumentException("Fixture root is not a directory: " + fixtureRoot);
        }
        if (!SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("sessionId must match " + SESSION_ID.pattern());
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (workers < 1 || workers > 128) {
            throw new IllegalArgumentException("workers must be between 1 and 128");
        }

        // B1 intentionally exposes only the control profile. B2 owns impairment behavior.
        if (profile != MediaLabProfile.N0) {
            throw new IllegalArgumentException(
                    "Profile " + profile + " is reserved for M0-B2; B1 supports N0 only");
        }
    }
}
