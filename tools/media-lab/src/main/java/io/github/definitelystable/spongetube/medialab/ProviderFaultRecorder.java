package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe producer of the provider-fault-events-v1 artifact. Sequence and
 * fault id are assigned here, starting at 1. Every appended event rewrites the
 * whole JSON document through a temp file and an atomic move, so a reader never
 * observes a partially written document.
 */
final class ProviderFaultRecorder {

    private static final String TEMP_SUFFIX = ".tmp";

    private final Path path;
    private final String runId;
    private final String sessionId;
    private final String scenarioFamily;
    private final String variant;
    private final String scenarioHash;
    private final long providerWallClockEpochMs;
    private final List<ProviderFaultEvent> events = new ArrayList<>();
    private long sequence;
    private long faultIds;

    ProviderFaultRecorder(
            Path path,
            String runId,
            String sessionId,
            String scenarioFamily,
            String variant,
            String scenarioHash,
            long providerWallClockEpochMs) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        this.runId = runId;
        this.sessionId = sessionId;
        this.scenarioFamily = scenarioFamily;
        this.variant = variant;
        this.scenarioHash = scenarioHash;
        this.providerWallClockEpochMs = providerWallClockEpochMs;
        writeDocument();
    }

    /** Assigns identity to a pending event and atomically rewrites the document. */
    synchronized ProviderFaultEvent append(ProviderFaultEvent pending) throws IOException {
        ProviderFaultEvent event = pending.withIdentity(++sequence, "fault-" + (++faultIds));
        events.add(event);
        writeDocument();
        return event;
    }

    /** Current document; identical to the artifact file and to the control-plane response. */
    synchronized String json() {
        return documentJson();
    }

    private void writeDocument() throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = path.resolveSibling(path.getFileName() + TEMP_SUFFIX);
        Files.writeString(
                temp,
                documentJson(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(
                    temp,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notSupported) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String documentJson() {
        StringBuilder json = new StringBuilder(320 + events.size() * 256);
        json.append('{');
        json.append(Json.quote("schemaVersion")).append(":1,");
        json.append(Json.quote("runId")).append(':').append(Json.quote(runId)).append(',');
        json.append(Json.quote("sessionId")).append(':').append(Json.quote(sessionId)).append(',');
        json.append(Json.quote("scenarioFamily")).append(':').append(Json.quote(scenarioFamily)).append(',');
        json.append(Json.quote("variant")).append(':').append(Json.quote(variant)).append(',');
        json.append(Json.quote("primaryPlane")).append(':').append(Json.quote("PROVIDER")).append(',');
        json.append(Json.quote("scenarioHash")).append(':').append(Json.quote(scenarioHash)).append(',');
        json.append(Json.quote("clockDomain")).append(':')
                .append(Json.quote("HOST_MEDIA_LAB_MONOTONIC")).append(',');
        json.append(Json.quote("providerWallClockDomain")).append(':')
                .append(Json.quote("PROVIDER_WALL_CLOCK")).append(',');
        json.append(Json.quote("providerWallClockEpochMs")).append(':')
                .append(providerWallClockEpochMs).append(',');
        json.append(Json.quote("events")).append(":[");
        for (int index = 0; index < events.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(events.get(index).toJson());
        }
        return json.append("]}").toString();
    }
}
