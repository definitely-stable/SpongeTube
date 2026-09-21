package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class FixtureManifestVerifier {

    private static final long MAX_CORPUS_BYTES = 40L * 1024L * 1024L;
    private static final long MAX_F0_BYTES = 2L * 1024L * 1024L;

    private FixtureManifestVerifier() {
    }

    static void verify(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        FixtureManifest manifest = FixtureManifest.load(normalized);
        Map<String, String> checksums = loadChecksums(normalized.resolve("checksums.sha256"));

        Set<String> manifestPaths = new HashSet<>();
        for (FixtureManifest.ResourceMetadata resource : manifest.resources()) {
            if (!manifestPaths.add(resource.relativePath())) {
                throw new IOException("Duplicate manifest path: " + resource.relativePath());
            }

            Path file = normalized.resolve(resource.relativePath()).normalize();
            if (!file.startsWith(normalized)) {
                throw new IOException("Resource escapes fixture root: " + resource.relativePath());
            }
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing or non-regular resource: " + resource.relativePath());
            }

            long actualSize = Files.size(file);
            if (actualSize != resource.sizeBytes()) {
                throw new IOException(
                        "Size mismatch for " + resource.relativePath()
                                + ": manifest=" + resource.sizeBytes()
                                + ", actual=" + actualSize);
            }

            String actualHash = sha256(file);
            if (!actualHash.equals(resource.sha256())) {
                throw new IOException("Manifest SHA-256 mismatch for " + resource.relativePath());
            }

            String checksumHash = checksums.get(resource.relativePath());
            if (!actualHash.equals(checksumHash)) {
                throw new IOException("checksums.sha256 mismatch for " + resource.relativePath());
            }
        }

        if (!checksums.keySet().equals(manifestPaths)) {
            throw new IOException(
                    "Manifest/checksum path sets differ: manifest="
                            + manifestPaths.size()
                            + ", checksums="
                            + checksums.size());
        }

        Set<String> actualPayloadPaths = discoverPayloads(normalized);
        if (!actualPayloadPaths.equals(manifestPaths)) {
            Set<String> missingFromManifest = new HashSet<>(actualPayloadPaths);
            missingFromManifest.removeAll(manifestPaths);

            Set<String> missingOnDisk = new HashSet<>(manifestPaths);
            missingOnDisk.removeAll(actualPayloadPaths);

            throw new IOException(
                    "Fixture payload set mismatch. Unlisted="
                            + missingFromManifest
                            + ", missing="
                            + missingOnDisk);
        }

        long totalCorpusBytes = 0;
        for (FixtureManifest.FixtureMetadata fixture : manifest.fixtures()) {
            long actualBytes = fixture.resources().stream()
                    .mapToLong(FixtureManifest.ResourceMetadata::sizeBytes)
                    .sum();
            if (actualBytes != fixture.actualBytes()) {
                throw new IOException(
                        "actualBytes mismatch for " + fixture.fixtureId()
                                + ": manifest=" + fixture.actualBytes()
                                + ", resources=" + actualBytes);
            }

            long mediaPayloadBytes = fixture.resources().stream()
                    .filter(resource -> !"dash-manifest".equals(resource.role()))
                    .mapToLong(FixtureManifest.ResourceMetadata::sizeBytes)
                    .sum();
            if (mediaPayloadBytes != fixture.mediaPayloadBytes()) {
                throw new IOException(
                        "mediaPayloadBytes mismatch for " + fixture.fixtureId());
            }

            long expectedAverageBitrate = Math.round(
                    mediaPayloadBytes * 8.0d * 1000.0d / fixture.durationMs());
            if (expectedAverageBitrate != fixture.actualAverageBitrateBps()) {
                throw new IOException(
                        "actualAverageBitrateBps mismatch for " + fixture.fixtureId()
                                + ": manifest=" + fixture.actualAverageBitrateBps()
                                + ", computed=" + expectedAverageBitrate);
            }

            if ("F1".equals(fixture.fixtureId())) {
                verifyF1(fixture);
            }
            if ("F0".equals(fixture.fixtureId()) && fixture.actualBytes() > MAX_F0_BYTES) {
                throw new IOException("F0 exceeds 2 MiB budget: " + fixture.actualBytes());
            }

            totalCorpusBytes = Math.addExact(totalCorpusBytes, fixture.actualBytes());
        }

        if (totalCorpusBytes > MAX_CORPUS_BYTES) {
            throw new IOException(
                    "Fixture corpus exceeds 40 MiB budget: " + totalCorpusBytes);
        }
    }

    private static void verifyF1(FixtureManifest.FixtureMetadata fixture) throws IOException {
        long manifestCount = fixture.resources().stream()
                .filter(resource -> "dash-manifest".equals(resource.role()))
                .count();
        long videoInitCount = fixture.resources().stream()
                .filter(resource -> "video-init".equals(resource.role()))
                .count();
        long audioInitCount = fixture.resources().stream()
                .filter(resource -> "audio-init".equals(resource.role()))
                .count();
        long videoSegmentCount = fixture.resources().stream()
                .filter(resource -> "video-segment".equals(resource.role()))
                .count();
        long audioSegmentCount = fixture.resources().stream()
                .filter(resource -> "audio-segment".equals(resource.role()))
                .count();

        if (manifestCount != 1
                || videoInitCount != 1
                || audioInitCount != 1
                || videoSegmentCount < 1
                || audioSegmentCount < 1) {
            throw new IOException(
                    "F1 role contract failed: manifest=" + manifestCount
                            + ", videoInit=" + videoInitCount
                            + ", audioInit=" + audioInitCount
                            + ", videoSegments=" + videoSegmentCount
                            + ", audioSegments=" + audioSegmentCount);
        }

        long videoBytes = fixture.resources().stream()
                .filter(resource -> resource.role().startsWith("video-"))
                .mapToLong(FixtureManifest.ResourceMetadata::sizeBytes)
                .sum();
        long audioBytes = fixture.resources().stream()
                .filter(resource -> resource.role().startsWith("audio-"))
                .mapToLong(FixtureManifest.ResourceMetadata::sizeBytes)
                .sum();

        long expectedReferenceBitrate = Math.round(
                (videoBytes + audioBytes) * 8.0d * 1000.0d / fixture.durationMs());

        if (fixture.referencePlaybackBitrateBps() == null
                || fixture.referencePlaybackBitrateBps() != expectedReferenceBitrate) {
            throw new IOException(
                    "F1 referencePlaybackBitrateBps mismatch: manifest="
                            + fixture.referencePlaybackBitrateBps()
                            + ", computed="
                            + expectedReferenceBitrate);
        }
    }

    private static Map<String, String> loadChecksums(Path path) throws IOException {
        HashMap<String, String> result = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int separator = trimmed.indexOf("  ");
            if (separator <= 0) {
                throw new IOException("Invalid checksum line: " + line);
            }

            String hash = trimmed.substring(0, separator);
            String relativePath = trimmed.substring(separator + 2);

            if (!hash.matches("[0-9a-f]{64}") || relativePath.isBlank()) {
                throw new IOException("Invalid checksum line: " + line);
            }
            if (result.putIfAbsent(relativePath, hash) != null) {
                throw new IOException("Duplicate checksum path: " + relativePath);
            }
        }
        return Map.copyOf(result);
    }

    private static Set<String> discoverPayloads(Path root) throws IOException {
        HashSet<String> payloads = new HashSet<>();

        for (String fixtureId : new String[] {"F0", "F1"}) {
            Path fixtureRoot = root.resolve(fixtureId);
            if (!Files.isDirectory(fixtureRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing fixture directory: " + fixtureId);
            }

            try (Stream<Path> stream = Files.walk(fixtureRoot)) {
                for (Path path : stream.toList()) {
                    if (path.equals(fixtureRoot)) {
                        continue;
                    }
                    if (Files.isSymbolicLink(path)) {
                        throw new IOException("Symbolic links are forbidden: " + path);
                    }
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        payloads.add(root.relativize(path).toString().replace('\\', '/'));
                    }
                }
            }
        }

        return Set.copyOf(payloads);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
