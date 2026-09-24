package io.github.definitelystable.spongetube.medialab;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class JsonLineTraceWriter implements Closeable {

    private final BufferedWriter writer;
    private boolean closed;

    JsonLineTraceWriter(Path path) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    synchronized void append(RequestTrace trace) throws IOException {
        appendLine(trace.toJsonLine());
    }

    synchronized void append(SessionEvent event) throws IOException {
        appendLine(event.toJsonLine());
    }

    synchronized void append(OriginGateEvent event) throws IOException {
        appendLine(event.toJsonLine());
    }

    private void appendLine(String line) throws IOException {
        ensureOpen();
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            writer.close();
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Trace writer is closed");
        }
    }
}
