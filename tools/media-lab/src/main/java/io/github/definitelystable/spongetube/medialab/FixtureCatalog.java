package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class FixtureCatalog {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path root;
    private final Map<String, FixtureResource> resources;

    private FixtureCatalog(Path root, Map<String, FixtureResource> resources) {
        this.root = root;
        this.resources = Collections.unmodifiableMap(resources);
    }

    static FixtureCatalog load(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("Fixture root must not be a symbolic link: " + normalized);
        }
        Path realRoot = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fixture root is not a directory: " + realRoot);
        }

        Map<String, FixtureResource> resources = new HashMap<>();

        try (Stream<Path> fixtureDirs = Files.list(realRoot)) {
            for (Path fixtureDir : fixtureDirs.sorted().toList()) {
                if (Files.isSymbolicLink(fixtureDir)) {
                    throw new IOException("Symbolic links are not allowed in fixture tree: " + fixtureDir);
                }
                if (!Files.isDirectory(fixtureDir, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }

                String fixtureId = fixtureDir.getFileName().toString();
                validateSegment(fixtureId, "fixture id");

                try (Stream<Path> paths = Files.walk(fixtureDir)) {
                    for (Path path : paths.sorted().toList()) {
                        if (path.equals(fixtureDir)) {
                            continue;
                        }
                        if (Files.isSymbolicLink(path)) {
                            throw new IOException("Symbolic links are not allowed in fixture tree: " + path);
                        }
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            continue;
                        }

                        Path relative = fixtureDir.relativize(path);
                        StringBuilder resourceId = new StringBuilder();
                        for (Path part : relative) {
                            String segment = part.toString();
                            validateSegment(segment, "resource path segment");
                            if (!resourceId.isEmpty()) {
                                resourceId.append('/');
                            }
                            resourceId.append(segment);
                        }

                        String id = resourceId.toString();
                        String urlPath = "/fixtures/" + fixtureId + "/" + id;
                        FixtureResource resource = new FixtureResource(
                                fixtureId,
                                id,
                                urlPath,
                                path.toRealPath(LinkOption.NOFOLLOW_LINKS),
                                Files.size(path),
                                ContentTypes.forPath(id));

                        if (resources.putIfAbsent(urlPath, resource) != null) {
                            throw new IOException("Duplicate fixture URL path: " + urlPath);
                        }
                    }
                }
            }
        }

        return new FixtureCatalog(realRoot, resources);
    }

    FixtureResource findRawPath(String rawPath) {
        if (rawPath == null || rawPath.indexOf('%') >= 0 || rawPath.indexOf('\\') >= 0) {
            return null;
        }
        return resources.get(rawPath);
    }

    int size() {
        return resources.size();
    }

    Path root() {
        return root;
    }

    private static void validateSegment(String value, String description) throws IOException {
        if (!SEGMENT.matcher(value).matches() || ".".equals(value) || "..".equals(value)) {
            throw new IOException("Invalid " + description + ": " + value);
        }
    }
}
