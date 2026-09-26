package io.github.definitelystable.spongetube.medialab;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class CliArguments {

    private CliArguments() {
    }

    static MediaLabConfig parse(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            throw new HelpRequested();
        }
        if (!"serve".equals(args[0])) {
            throw new IllegalArgumentException("Expected command: serve");
        }

        Map<String, String> values = new HashMap<>();
        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            if (!argument.startsWith("--") || !argument.contains("=")) {
                throw new IllegalArgumentException("Expected --key=value, got: " + argument);
            }
            int separator = argument.indexOf('=');
            String key = argument.substring(2, separator);
            String value = argument.substring(separator + 1);
            if (key.isBlank() || value.isBlank()) {
                throw new IllegalArgumentException("Empty key or value in: " + argument);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate option: --" + key);
            }
        }

        for (String key : values.keySet()) {
            if (!switch (key) {
                case "fixture-root",
                        "trace",
                        "session-id",
                        "profile",
                        "data-port",
                        "data-workers",
                        "control-port",
                        "control-workers",
                        "reference-playback-bitrate-bps",
                        "no-progress-start-after-ms",
                        "write-quantum-bytes",
                        "provider-variant",
                        "provider-wall-clock-epoch-ms" -> true;
                default -> false;
            }) {
                throw new IllegalArgumentException("Unknown option: --" + key);
            }
        }

        String fixtureRoot = required(values, "fixture-root");
        String trace = required(values, "trace");
        String sessionId = required(values, "session-id");

        int dataPort = parseInt(values.getOrDefault("data-port", "0"), "data-port");
        int dataWorkers = parseInt(values.getOrDefault("data-workers", "8"), "data-workers");
        int controlPort = parseInt(values.getOrDefault("control-port", "0"), "control-port");
        int controlWorkers = parseInt(values.getOrDefault("control-workers", "2"), "control-workers");
        int writeQuantumBytes = parseInt(
                values.getOrDefault(
                        "write-quantum-bytes",
                        Integer.toString(MediaLabConfig.DEFAULT_WRITE_QUANTUM_BYTES)),
                "write-quantum-bytes");
        MediaLabProfile profile = MediaLabProfile.parse(values.getOrDefault("profile", "N0"));

        Long referenceBitrate = optionalLong(values, "reference-playback-bitrate-bps");
        Long noProgressStartAfterMs = optionalLong(values, "no-progress-start-after-ms");
        ProviderVariant providerVariant = ProviderVariant.parse(values.get("provider-variant"));
        Long providerWallClockEpochMs = optionalLong(values, "provider-wall-clock-epoch-ms");

        return new MediaLabConfig(
                Path.of(fixtureRoot),
                Path.of(trace),
                sessionId,
                profile,
                dataPort,
                dataWorkers,
                controlPort,
                controlWorkers,
                referenceBitrate,
                noProgressStartAfterMs,
                writeQuantumBytes,
                providerVariant,
                providerWallClockEpochMs);
    }

    static String usage() {
        return """
                Usage:
                  media-lab serve \
                    --fixture-root=<path> \
                    --trace=<path> \
                    --session-id=<id> \
                    [--profile=N0|N1|N4|N4R|N8|N9|N10] \
                    [--data-port=0] \
                    [--data-workers=8] \
                    [--control-port=0] \
                    [--control-workers=2] \
                    [--write-quantum-bytes=8192] \
                    [--reference-playback-bitrate-bps=<bps>] \
                    [--no-progress-start-after-ms=<ms>] \
                    [--provider-variant=<VARIANT>] \
                    [--provider-wall-clock-epoch-ms=<ms>]

                  media-lab summarize \
                    --trace=<requests.jsonl> \
                    --output=<network-summary.json> \
                    [--expected-session-id=<id>] \
                    [--expected-scenario-id=<id>] \
                    [--expected-scenario-hash=<sha256>]

                N1 requires --reference-playback-bitrate-bps.
                N4 requires --no-progress-start-after-ms; canonical duration is 120000 ms.
                N4R uses the manual media-body gate under /__lab/gate/media/*.
                N8/N9/N10 require --provider-variant and reject N1/N4 parameters:
                  N8: HTTP_403_BARE
                  N9: HTTP_429_RETRY_AFTER_DELAY_SECONDS | HTTP_429_RETRY_AFTER_HTTP_DATE |
                      HTTP_429_RETRY_AFTER_ABSENT | HTTP_429_RETRY_AFTER_MALFORMED
                  N10: BINDING_EXPIRY_REFRESH | BINDING_REFRESH_INCOMPATIBLE | BINDING_REFRESH_FAILED
                Provider profiles use a fixed virtual provider wall clock
                (--provider-wall-clock-epoch-ms, default 1790337600000) and serve provider
                fault evidence at /__lab/provider/events.
                Request trace, session events and calibration summary share the --trace basename.
                summarize accepts request trace schema v2 and rejects mixed data-plane session/scenario identity.
                Expected identity options make zero-network cache hits explicit while still validating non-empty traces.
                """;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required option: --" + key);
        }
        return value;
    }

    private static Long optionalLong(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null ? null : parseLong(value, key);
    }

    private static int parseInt(String value, String key) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer for --" + key + ": " + value);
        }
    }

    private static long parseLong(String value, String key) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid long for --" + key + ": " + value);
        }
    }

    static final class HelpRequested extends RuntimeException {
    }
}
