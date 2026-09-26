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
        int controlWorkers,
        Long referencePlaybackBitrateBps,
        Long noProgressStartAfterMs,
        int writeQuantumBytes,
        ProviderVariant providerVariant,
        Long providerWallClockEpochMs) {

    static final int DEFAULT_WRITE_QUANTUM_BYTES = 8 * 1024;

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    MediaLabConfig(
            Path fixtureRoot,
            Path tracePath,
            String sessionId,
            MediaLabProfile profile,
            int dataPort,
            int dataWorkers,
            int controlPort,
            int controlWorkers,
            Long referencePlaybackBitrateBps,
            Long noProgressStartAfterMs,
            int writeQuantumBytes) {
        this(
                fixtureRoot,
                tracePath,
                sessionId,
                profile,
                dataPort,
                dataWorkers,
                controlPort,
                controlWorkers,
                referencePlaybackBitrateBps,
                noProgressStartAfterMs,
                writeQuantumBytes,
                null,
                null);
    }

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
        if (writeQuantumBytes < 1 || writeQuantumBytes > 1024 * 1024) {
            throw new IllegalArgumentException(
                    "write-quantum-bytes must be between 1 and 1048576");
        }

        switch (profile) {
            case N0 -> {
                if (referencePlaybackBitrateBps != null || noProgressStartAfterMs != null) {
                    throw new IllegalArgumentException(
                            "N0 does not accept N1/N4-specific scenario parameters");
                }
                rejectProviderOptions(profile, providerVariant, providerWallClockEpochMs);
            }
            case N1 -> {
                if (referencePlaybackBitrateBps == null || referencePlaybackBitrateBps <= 0) {
                    throw new IllegalArgumentException(
                            "N1 requires --reference-playback-bitrate-bps > 0");
                }
                if (noProgressStartAfterMs != null) {
                    throw new IllegalArgumentException(
                            "N1 does not accept --no-progress-start-after-ms");
                }
                rejectProviderOptions(profile, providerVariant, providerWallClockEpochMs);
            }
            case N4 -> {
                if (noProgressStartAfterMs == null || noProgressStartAfterMs < 0) {
                    throw new IllegalArgumentException(
                            "N4 requires --no-progress-start-after-ms >= 0");
                }
                if (referencePlaybackBitrateBps != null) {
                    throw new IllegalArgumentException(
                            "N4 does not accept --reference-playback-bitrate-bps");
                }
                rejectProviderOptions(profile, providerVariant, providerWallClockEpochMs);
            }
            case N4R -> {
                if (referencePlaybackBitrateBps != null || noProgressStartAfterMs != null) {
                    throw new IllegalArgumentException(
                            "N4R uses a manual media-body gate and accepts no N1/N4 parameters");
                }
                rejectProviderOptions(profile, providerVariant, providerWallClockEpochMs);
            }
            case N8, N9, N10 -> {
                if (referencePlaybackBitrateBps != null || noProgressStartAfterMs != null) {
                    throw new IllegalArgumentException(
                            profile + " does not accept N1/N4-specific scenario parameters");
                }
                if (providerVariant == null) {
                    throw new IllegalArgumentException(
                            profile + " requires --provider-variant");
                }
                if (providerVariant.family() != profile) {
                    throw new IllegalArgumentException(
                            "Provider variant " + providerVariant.name() + " belongs to "
                                    + providerVariant.family().name() + ", not " + profile.name());
                }
                if (providerWallClockEpochMs == null) {
                    providerWallClockEpochMs =
                            ProviderSimulator.DEFAULT_PROVIDER_WALL_CLOCK_EPOCH_MS;
                }
            }
        }
    }

    Path sessionTracePath() {
        return siblingArtifact(".events");
    }

    Path calibrationPath() {
        return siblingArtifact(".calibration");
    }

    Path gateTracePath() {
        return siblingArtifact(".gate");
    }

    /** Sibling document holding provider-fault-events-v1 for N8/N9/N10 sessions. */
    Path providerFaultsPath() {
        String fileName = tracePath.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String stem = extension > 0 ? fileName.substring(0, extension) : fileName;
        return tracePath.resolveSibling(stem + ".provider-faults.json");
    }

    ResolvedScenario resolvedScenario() {
        return ResolvedScenario.resolve(this);
    }

    private Path siblingArtifact(String suffix) {
        String fileName = tracePath.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String stem = extension > 0 ? fileName.substring(0, extension) : fileName;
        String ext = extension > 0 ? fileName.substring(extension) : "";
        return tracePath.resolveSibling(stem + suffix + ext);
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

    private static void rejectProviderOptions(
            MediaLabProfile profile,
            ProviderVariant providerVariant,
            Long providerWallClockEpochMs) {
        if (providerVariant != null) {
            throw new IllegalArgumentException(
                    profile.name() + " does not accept --provider-variant");
        }
        if (providerWallClockEpochMs != null) {
            throw new IllegalArgumentException(
                    profile.name() + " does not accept --provider-wall-clock-epoch-ms");
        }
    }
}
