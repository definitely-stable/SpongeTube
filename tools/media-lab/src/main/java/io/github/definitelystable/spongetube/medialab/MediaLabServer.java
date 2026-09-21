package io.github.definitelystable.spongetube.medialab;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class MediaLabServer implements AutoCloseable {

    private static final int COPY_BUFFER_BYTES = 8 * 1024;

    private final MediaLabConfig config;
    private final FixtureCatalog catalog;
    private final JsonLineTraceWriter traceWriter;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private MediaLabServer(
            MediaLabConfig config,
            FixtureCatalog catalog,
            JsonLineTraceWriter traceWriter,
            HttpServer server,
            ExecutorService executor) {
        this.config = config;
        this.catalog = catalog;
        this.traceWriter = traceWriter;
        this.server = server;
        this.executor = executor;
    }

    static MediaLabServer create(MediaLabConfig config) throws IOException {
        FixtureCatalog catalog = FixtureCatalog.load(config.fixtureRoot());

        HttpServer server = null;
        ExecutorService executor = null;
        JsonLineTraceWriter traceWriter = null;
        try {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            server = HttpServer.create(new InetSocketAddress(loopback, config.port()), 0);
            AtomicLong workerIds = new AtomicLong();
            executor = Executors.newFixedThreadPool(
                    config.workers(),
                    runnable -> {
                        Thread thread = new Thread(
                                runnable,
                                "media-lab-http-" + workerIds.incrementAndGet());
                        thread.setDaemon(false);
                        return thread;
                    });
            traceWriter = new JsonLineTraceWriter(config.tracePath());

            MediaLabServer mediaLab = new MediaLabServer(
                    config,
                    catalog,
                    traceWriter,
                    server,
                    executor);

            server.createContext("/", mediaLab::handle);
            server.setExecutor(executor);
            return mediaLab;
        } catch (IOException | RuntimeException exception) {
            if (server != null) {
                server.stop(0);
            }
            if (executor != null) {
                executor.shutdownNow();
            }
            if (traceWriter != null) {
                traceWriter.close();
            }
            throw exception;
        }
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String readyJson() {
        return "{"
                + Json.quote("schemaVersion") + ":1,"
                + Json.quote("event") + ":" + Json.quote("MEDIA_LAB_READY") + ","
                + Json.quote("host") + ":" + Json.quote("127.0.0.1") + ","
                + Json.quote("port") + ":" + port() + ","
                + Json.quote("sessionId") + ":" + Json.quote(config.sessionId()) + ","
                + Json.quote("profileId") + ":" + Json.quote(config.profile().name()) + ","
                + Json.quote("workers") + ":" + config.workers() + ","
                + Json.quote("catalogResources") + ":" + catalog.size()
                + "}";
    }

    String configJson() {
        return "{"
                + Json.quote("schemaVersion") + ":1,"
                + Json.quote("sessionId") + ":" + Json.quote(config.sessionId()) + ","
                + Json.quote("profileId") + ":" + Json.quote(config.profile().name()) + ","
                + Json.quote("host") + ":" + Json.quote("127.0.0.1") + ","
                + Json.quote("port") + ":" + port() + ","
                + Json.quote("workers") + ":" + config.workers() + ","
                + Json.quote("catalogResources") + ":" + catalog.size()
                + "}";
    }

    private void handle(HttpExchange exchange) {
        long acceptedAt = System.nanoTime();
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getRawPath();
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        RequestTraceAccumulator trace = new RequestTraceAccumulator(
                config,
                requestIds.incrementAndGet(),
                acceptedAt,
                method,
                rawPath,
                rangeHeader);

        try {
            route(exchange, trace, rawPath, method, rangeHeader);
        } catch (IOException exception) {
            trace.outcome = trace.firstBodyWriteAtMonotonicNs == null
                    ? TraceOutcome.SERVER_IO_ERROR
                    : TraceOutcome.CLIENT_DISCONNECTED;
            System.err.println("media-lab request I/O failure: " + exception.getMessage());
        } catch (RuntimeException exception) {
            trace.outcome = TraceOutcome.SERVER_IO_ERROR;
            System.err.println("media-lab request failure: " + exception.getMessage());
        } finally {
            exchange.close();
            try {
                traceWriter.append(trace.complete(System.nanoTime()));
            } catch (IOException traceFailure) {
                System.err.println("media-lab trace failure: " + traceFailure.getMessage());
            }
        }
    }

    private void route(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            String rawPath,
            String method,
            String rangeHeader) throws IOException {

        if ("/__lab/health".equals(rawPath)) {
            if (!"GET".equals(method)) {
                sendMethodNotAllowed(exchange, trace, "GET");
                return;
            }
            sendJson(exchange, trace, 200,
                    "{" + Json.quote("status") + ":" + Json.quote("ok") + ","
                            + Json.quote("sessionId") + ":" + Json.quote(config.sessionId()) + "}");
            return;
        }

        if ("/__lab/config".equals(rawPath)) {
            if (!"GET".equals(method)) {
                sendMethodNotAllowed(exchange, trace, "GET");
                return;
            }
            sendJson(exchange, trace, 200, configJson());
            return;
        }

        if (!rawPath.startsWith("/fixtures/")) {
            sendNotFound(exchange, trace);
            return;
        }

        FixtureResource resource = catalog.findRawPath(rawPath);
        if (resource == null) {
            sendNotFound(exchange, trace);
            return;
        }

        trace.fixtureId = resource.fixtureId();
        trace.resourceId = resource.resourceId();

        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            sendMethodNotAllowed(exchange, trace, "GET, HEAD");
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Accept-Ranges", "bytes");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Type", resource.contentType());

        if ("HEAD".equals(method)) {
            headers.set("Content-Length", Long.toString(resource.length()));
            trace.status = 200;
            trace.plannedResponseBytes = 0;
            trace.outcome = TraceOutcome.SUCCESS;
            exchange.sendResponseHeaders(200, -1);
            return;
        }

        RangeDecision decision = RangeParser.parse(rangeHeader, resource.length());

        if (decision instanceof RangeDecision.Unsatisfiable) {
            headers.set("Content-Range", "bytes */" + resource.length());
            trace.status = 416;
            trace.plannedResponseBytes = 0;
            trace.outcome = TraceOutcome.RANGE_UNSATISFIABLE;
            exchange.sendResponseHeaders(416, -1);
            return;
        }

        long start;
        long endExclusive;
        int status;

        if (decision instanceof RangeDecision.Partial partial) {
            start = partial.startInclusive();
            endExclusive = partial.endExclusive();
            status = 206;
            trace.resolvedRangeStart = start;
            trace.resolvedRangeEndExclusive = endExclusive;
            headers.set(
                    "Content-Range",
                    "bytes " + start + "-" + (endExclusive - 1) + "/" + resource.length());
        } else {
            start = 0;
            endExclusive = resource.length();
            status = 200;
            trace.resolvedRangeStart = start;
            trace.resolvedRangeEndExclusive = endExclusive;
        }

        long responseLength = endExclusive - start;
        trace.status = status;
        trace.plannedResponseBytes = responseLength;

        if (responseLength == 0) {
            headers.set("Content-Length", "0");
            trace.outcome = TraceOutcome.SUCCESS;
            exchange.sendResponseHeaders(status, -1);
            return;
        }

        exchange.sendResponseHeaders(status, responseLength);
        writeResource(exchange, trace, resource, start, responseLength);
        trace.outcome = TraceOutcome.SUCCESS;
    }

    private void writeResource(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            FixtureResource resource,
            long start,
            long length) throws IOException {

        try (InputStream input = Files.newInputStream(resource.file());
             OutputStream output = exchange.getResponseBody()) {

            if (start > 0) {
                input.skipNBytes(start);
            }

            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long remaining = length;

            while (remaining > 0) {
                int requested = (int) Math.min(buffer.length, remaining);
                int read = input.read(buffer, 0, requested);
                if (read < 0) {
                    throw new IOException("Unexpected EOF while serving " + resource.resourceId());
                }

                trace.markFirstBodyWrite();
                output.write(buffer, 0, read);
                trace.bodyBytesWritten += read;
                remaining -= read;
            }
        }
    }

    private static void sendJson(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            int status,
            String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        trace.status = status;
        trace.plannedResponseBytes = body.length;
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            trace.markFirstBodyWrite();
            output.write(body);
            trace.bodyBytesWritten += body.length;
        }
        trace.outcome = TraceOutcome.SUCCESS;
    }

    private static void sendNotFound(
            HttpExchange exchange,
            RequestTraceAccumulator trace) throws IOException {
        trace.outcome = TraceOutcome.NOT_FOUND;
        sendErrorJson(exchange, trace, 404, "not_found");
        trace.outcome = TraceOutcome.NOT_FOUND;
    }

    private static void sendMethodNotAllowed(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        trace.outcome = TraceOutcome.METHOD_NOT_ALLOWED;
        sendErrorJson(exchange, trace, 405, "method_not_allowed");
        trace.outcome = TraceOutcome.METHOD_NOT_ALLOWED;
    }

    private static void sendErrorJson(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            int status,
            String error) throws IOException {
        String json = "{" + Json.quote("error") + ":" + Json.quote(error) + "}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        trace.status = status;
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            trace.plannedResponseBytes = 0;
            exchange.sendResponseHeaders(status, -1);
            return;
        }

        trace.plannedResponseBytes = body.length;
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            trace.markFirstBodyWrite();
            output.write(body);
            trace.bodyBytesWritten += body.length;
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        server.stop(0);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            traceWriter.close();
        }
    }
}
