package io.github.definitelystable.spongetube.medialab;

import java.nio.file.Path;

record FixtureResource(
        String fixtureId,
        String resourceId,
        String urlPath,
        Path file,
        long length,
        String contentType) {
}
