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
                        "control-workers" -> true;
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
        MediaLabProfile profile = MediaLabProfile.parse(values.getOrDefault("profile", "N0"));

        return new MediaLabConfig(
                Path.of(fixtureRoot),
                Path.of(trace),
                sessionId,
                profile,
                dataPort,
                dataWorkers,
                controlPort,
                controlWorkers);
    }

    static String usage() {
        return """
                Usage:
                  media-lab serve \
                    --fixture-root=<path> \
                    --trace=<path> \
                    --session-id=<id> \
                    [--profile=N0] \
                    [--data-port=0] \
                    [--data-workers=8] \
                    [--control-port=0] \
                    [--control-workers=2]

                Data and control listeners are separate loopback-only servers.
                M0-B1 supports profile N0 only. N1/N4 arrive in M0-B2.
                """;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required option: --" + key);
        }
        return value;
    }

    private static int parseInt(String value, String key) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer for --" + key + ": " + value);
        }
    }

    static final class HelpRequested extends RuntimeException {
    }
}
