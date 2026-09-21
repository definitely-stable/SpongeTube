package io.github.definitelystable.spongetube.medialab;

final class ContentTypes {

    private ContentTypes() {
    }

    static String forPath(String resourceId) {
        String lower = resourceId.toLowerCase();
        if (lower.endsWith(".mpd")) {
            return "application/dash+xml";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".m4s")) {
            return "video/iso.segment";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".sha256")) {
            return "text/plain; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
