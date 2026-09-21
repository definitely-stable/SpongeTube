package io.github.definitelystable.spongetube.medialab;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class CliOptionParser {

    private CliOptionParser() {
    }

    static Map<String, String> parse(
            String[] args,
            int startIndex,
            Set<String> allowedKeys) {
        Map<String, String> values = new HashMap<>();

        for (int index = startIndex; index < args.length; index++) {
            String argument = args[index];
            if (!argument.startsWith("--") || !argument.contains("=")) {
                throw new IllegalArgumentException(
                        "Expected --key=value, got: " + argument);
            }

            int separator = argument.indexOf('=');
            String key = argument.substring(2, separator);
            String value = argument.substring(separator + 1);

            if (key.isBlank() || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Empty key or value in: " + argument);
            }
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException(
                        "Unknown option: --" + key);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate option: --" + key);
            }
        }

        return values;
    }

    static String required(
            Map<String, String> values,
            String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing required option: --" + key);
        }
        return value;
    }
}
