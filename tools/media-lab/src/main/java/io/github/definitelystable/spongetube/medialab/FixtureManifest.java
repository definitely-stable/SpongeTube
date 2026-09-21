package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class FixtureManifest {

    record ResourceMetadata(
            String relativePath,
            String role,
            long sizeBytes,
            String sha256,
            String contentType) {
    }

    record FixtureMetadata(
            String fixtureId,
            String kind,
            long durationMs,
            long actualBytes,
            long mediaPayloadBytes,
            long actualAverageBitrateBps,
            Long referencePlaybackBitrateBps,
            List<ResourceMetadata> resources) {
    }

    private final Map<String, FixtureMetadata> fixtures;
    private final Map<String, ResourceMetadata> resourcesByPath;

    private FixtureManifest(
            Map<String, FixtureMetadata> fixtures,
            Map<String, ResourceMetadata> resourcesByPath) {
        this.fixtures = Map.copyOf(fixtures);
        this.resourcesByPath = Map.copyOf(resourcesByPath);
    }

    static Optional<FixtureManifest> tryLoad(Path root) throws IOException {
        Path path = root.resolve("manifest.json");
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(load(root));
    }

    static FixtureManifest load(Path root) throws IOException {
        Path manifestPath = root.resolve("manifest.json");
        String json = Files.readString(manifestPath, StandardCharsets.UTF_8);

        Map<String, Object> document = object(MiniJsonParser.parse(json), "manifest root");
        requireLong(document, "schemaVersion", 1L);

        List<Object> fixtureList = array(document.get("fixtures"), "fixtures");
        if (fixtureList.isEmpty()) {
            throw new IOException("Fixture manifest must contain at least one fixture");
        }

        LinkedHashMap<String, FixtureMetadata> fixtures = new LinkedHashMap<>();
        LinkedHashMap<String, ResourceMetadata> resourcesByPath = new LinkedHashMap<>();

        for (Object rawFixture : fixtureList) {
            Map<String, Object> fixture = object(rawFixture, "fixture");
            String fixtureId = string(fixture, "fixtureId");
            String kind = string(fixture, "kind");
            long durationMs = positiveLong(fixture, "durationMs");
            long actualBytes = positiveLong(fixture, "actualBytes");
            long mediaPayloadBytes = positiveLong(fixture, "mediaPayloadBytes");
            long actualAverageBitrateBps = positiveLong(fixture, "actualAverageBitrateBps");
            Long referencePlaybackBitrateBps = nullablePositiveLong(
                    fixture.get("referencePlaybackBitrateBps"),
                    "referencePlaybackBitrateBps");

            List<ResourceMetadata> resources = new ArrayList<>();
            for (Object rawResource : array(fixture.get("resources"), "resources")) {
                Map<String, Object> resource = object(rawResource, "resource");
                ResourceMetadata metadata = new ResourceMetadata(
                        string(resource, "relativePath"),
                        string(resource, "role"),
                        positiveLong(resource, "sizeBytes"),
                        string(resource, "sha256"),
                        string(resource, "contentType"));

                if (!metadata.relativePath().startsWith(fixtureId + "/")) {
                    throw new IOException(
                            "Resource " + metadata.relativePath()
                                    + " does not belong to fixture " + fixtureId);
                }
                if (metadata.sha256().length() != 64
                        || !metadata.sha256().matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid SHA-256 for " + metadata.relativePath());
                }

                if (resourcesByPath.putIfAbsent(metadata.relativePath(), metadata) != null) {
                    throw new IOException(
                            "Duplicate fixture resource path: " + metadata.relativePath());
                }
                resources.add(metadata);
            }

            if (resources.isEmpty()) {
                throw new IOException("Fixture " + fixtureId + " has no resources");
            }

            FixtureMetadata metadata = new FixtureMetadata(
                    fixtureId,
                    kind,
                    durationMs,
                    actualBytes,
                    mediaPayloadBytes,
                    actualAverageBitrateBps,
                    referencePlaybackBitrateBps,
                    List.copyOf(resources));

            if (fixtures.putIfAbsent(fixtureId, metadata) != null) {
                throw new IOException("Duplicate fixture id: " + fixtureId);
            }
        }

        return new FixtureManifest(fixtures, resourcesByPath);
    }

    Optional<ResourceMetadata> resource(String rootRelativePath) {
        return Optional.ofNullable(resourcesByPath.get(rootRelativePath));
    }

    FixtureMetadata fixture(String fixtureId) {
        FixtureMetadata fixture = fixtures.get(fixtureId);
        if (fixture == null) {
            throw new IllegalArgumentException("Unknown fixture: " + fixtureId);
        }
        return fixture;
    }

    Collection<FixtureMetadata> fixtures() {
        return fixtures.values();
    }

    Collection<ResourceMetadata> resources() {
        return resourcesByPath.values();
    }

    private static Map<String, Object> object(Object value, String description) throws IOException {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IOException("Expected JSON object for " + description);
        }

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IOException("Non-string JSON key in " + description);
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Object> array(Object value, String description) throws IOException {
        if (!(value instanceof List<?> list)) {
            throw new IOException("Expected JSON array for " + description);
        }
        return new ArrayList<>(list);
    }

    private static String string(Map<String, Object> object, String key) throws IOException {
        Object value = object.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IOException("Expected non-empty string for " + key);
        }
        return string;
    }

    private static long positiveLong(Map<String, Object> object, String key) throws IOException {
        Long value = nullablePositiveLong(object.get(key), key);
        if (value == null) {
            throw new IOException("Expected positive integer for " + key);
        }
        return value;
    }

    private static Long nullablePositiveLong(Object value, String key) throws IOException {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Long number) || number <= 0) {
            throw new IOException("Expected positive integer or null for " + key);
        }
        return number;
    }

    private static void requireLong(Map<String, Object> object, String key, long expected)
            throws IOException {
        Object value = object.get(key);
        if (!(value instanceof Long number) || number != expected) {
            throw new IOException("Expected " + key + "=" + expected);
        }
    }
}
