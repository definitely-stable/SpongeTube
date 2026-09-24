package io.github.definitelystable.spongetube.medialab;

enum MediaLabProfile {
    N0,
    N1,
    N4,
    N4R;

    static MediaLabProfile parse(String value) {
        try {
            return value == null ? N0 : valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown profile: " + value);
        }
    }
}
