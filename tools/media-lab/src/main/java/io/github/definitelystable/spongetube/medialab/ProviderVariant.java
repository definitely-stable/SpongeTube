package io.github.definitelystable.spongetube.medialab;

/**
 * Deterministic provider-plane variant. Every variant belongs to exactly one
 * scenario family; a variant of another family is rejected by configuration
 * validation.
 */
enum ProviderVariant {

    HTTP_403_BARE(MediaLabProfile.N8),
    HTTP_429_RETRY_AFTER_DELAY_SECONDS(MediaLabProfile.N9),
    HTTP_429_RETRY_AFTER_HTTP_DATE(MediaLabProfile.N9),
    HTTP_429_RETRY_AFTER_ABSENT(MediaLabProfile.N9),
    HTTP_429_RETRY_AFTER_MALFORMED(MediaLabProfile.N9),
    BINDING_EXPIRY_REFRESH(MediaLabProfile.N10),
    BINDING_REFRESH_INCOMPATIBLE(MediaLabProfile.N10),
    BINDING_REFRESH_FAILED(MediaLabProfile.N10);

    private final MediaLabProfile family;

    ProviderVariant(MediaLabProfile family) {
        this.family = family;
    }

    MediaLabProfile family() {
        return family;
    }

    static ProviderVariant parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown provider variant: " + value);
        }
    }
}
