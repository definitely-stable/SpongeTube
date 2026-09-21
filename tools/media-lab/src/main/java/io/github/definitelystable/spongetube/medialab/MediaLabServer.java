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
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class MediaLabServer implements AutoCloseable {

    private final MediaLabConfig config;
    private final ResolvedScenario scenario;
    private final MonotonicClock clock;
    private final FixtureCatalog catalog;
    private final JsonLineTraceWriter requestTraceWriter;
    private final JsonLineTraceWriter eventTraceWriter;
    private final SessionCalibration calibration;
    private final SessionEventRecorder events;
    private final FixtureBodyWriter fixtureBodyWriter;
    private final HttpServer dataServer;
    private final HttpServer controlServer;
    private final ExecutorService dataExecutor;
    private final ExecutorService controlExecutor;
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private MediaLabServer(
            MediaLabConfig config,
            ResolvedScenario scenario,
            MonotonicClock clock,
            FixtureCatalog catalog,
            JsonLineTraceWriter requestTraceWriter,
            JsonLineTraceWriter eventTraceWriter,
            SessionCalibration calibration,
            SessionEventRecorder events,
            FixtureBodyWriter fixtureBodyWriter,
            HttpServer dataServer,
            HttpServer controlServer,
            ExecutorService dataExecutor,
            ExecutorService controlExecutor) {
        this.config = config;
        this.scenario = scenario;
        this.clock = clock;
        this.catalog = catalog;
        this.requestTraceWriter = requestTraceWriter;
        this.eventTraceWriter = eventTraceWriter;
        this.calibration = calibration;
        this.events = events;
        this.fixtureBodyWriter = fixtureBodyWriter;
        this.dataServer = dataServer;
        this.controlServer = controlServer;
        this.dataExecutor = dataExecutor;
        this.controlExecutor = controlExecutor;
    }

    static MediaLabServer create(MediaLabConfig config) throws IOException {
        MonotonicClock clock = SystemMonotonicClock.INSTANCE;
        return create(config, config.resolvedScenario(), clock, new SystemSleeper(clock));
    }

    static MediaLabServer create(
            MediaLabConfig config,
            ResolvedScenario scenario,
            MonotonicClock clock,
            Sleeper sleeper) throws IOException {
        FixtureCatalog catalog = FixtureCatalog.load(config.fixtureRoot());

        HttpServer dataServer = null;
        HttpServer controlServer = null;
        ExecutorService dataExecutor = null;
        ExecutorService controlExecutor = null;
        JsonLineTraceWriter requestTraceWriter = null;
        JsonLineTraceWriter eventTraceWriter = null;

        try {
            ensureArtifactsAbsent(config);

            InetAddress loopback = InetAddress.getByName("127.0.0.1");

            // Bind both listeners before creating evidence artifacts. A partial bind failure must
            // not leave an empty trace that could be mistaken for a valid scenario session.
            dataServer = HttpServer.create(new InetSocketAddress(loopback, config.dataPort()), 0);
            controlServer = HttpServer.create(new InetSocketAddress(loopback, config.controlPort()), 0);

            dataExecutor = newFixedThreadPool(config.dataWorkers(), "media-lab-data-");
            controlExecutor = newFixedThreadPool(config.controlWorkers(), "media-lab-control-");

            requestTraceWriter = new JsonLineTraceWriter(config.tracePath());
            eventTraceWriter = new JsonLineTraceWriter(config.sessionTracePath());

            SessionCalibration calibration = new SessionCalibration();
            SessionEventRecorder events =
                    new SessionEventRecorder(config, scenario, clock, eventTraceWriter);
            FixtureBodyWriter fixtureBodyWriter =
                    new FixtureBodyWriter(scenario, clock, sleeper, events, calibration);

            MediaLabServer mediaLab = new MediaLabServer(
                    config,
                    scenario,
                    clock,
                    catalog,
                    requestTraceWriter,
                    eventTraceWriter,
                    calibration,
                    events,
                    fixtureBodyWriter,
                    dataServer,
                    controlServer,
                    dataExecutor,
                    controlExecutor);

            dataServer.createContext("/", mediaLab::handleData);
            dataServer.setExecutor(dataExecutor);

            controlServer.createContext("/", mediaLab::handleControl);
            controlServer.setExecutor(controlExecutor);

            return mediaLab;
        } catch (IOException | RuntimeException exception) {
            if (dataServer != null) {
                dataServer.stop(0);
            }
            if (controlServer != null) {
                controlServer.stop(0);
            }
            if (dataExecutor != null) {
                dataExecutor.shutdownNow();
            }
            if (controlExecutor != null) {
                controlExecutor.shutdownNow();
            }
            closeQuietly(requestTraceWriter);
            closeQuietly(eventTraceWriter);
            deleteQuietly(config.tracePath());
            deleteQuietly(config.sessionTracePath());
            throw exception;
        }
    }

    void start() throws IOException {
        controlServer.start();
        try {
            dataServer.start();
            events.start();
        } catch (IOException | RuntimeException exception) {
            dataServer.stop(0);
            controlServer.stop(0);
            throw exception;
        }
    }

    int dataPort() {
        return dataServer.getAddress().getPort();
    }

    int controlPort() {
        return controlServer.getAddress().getPort();
    }

    String readyJson() {
        return "{"
                + Json.quote("schemaVersion") + ":3,"
                + Json.quote("event") + ":" + Json.quote("MEDIA_LAB_READY") + ","
                + Json.quote("host") + ":" + Json.quote("127.0.0.1") + ","
                + Json.quote("dataPort") + ":" + dataPort() + ","
                + Json.quote("controlPort") + ":" + controlPort() + ","
                + Json.quote("sessionId") + ":" + Json.quote(config.sessionId()) + ","
                + Json.quote("profileId") + ":" + Json.quote(config.profile().name()) + ","
                + Json.quote("scenarioId") + ":" + Json.quote(scenario.scenarioId()) + ","
                + Json.quote("scenarioHash") + ":" + Json.quote(scenario.scenarioHash()) + ","
                + Json.quote("dataWorkers") + ":" + config.dataWorkers() + ","
                + Json.quote("controlWorkers") + ":" + config.controlWorkers() + ","
                + Json.quote("catalogResources") + ":" + catalog.size() + ","
                + Json.quote("requestTrace") + ":" + Json.quote(config.tracePath().toString()) + ","
                + Json.quote("sessionTrace") + ":" + Json.quote(config.sessionTracePath().toString()) + ","
                + Json.quote("calibration") + ":" + Json.quote(config.calibrationPath().toString())
                + "}";
    }

    String configJson() {
        return "{"
                + Json.quote("schemaVersion") + ":3,"
                + Json.quote("sessionId") + ":" + Json.quote(config.sessionId()) + ","
                + Json.quote("profileId") + ":" + Json.quote(config.profile().name()) + ","
                + Json.quote("host") + ":" + Json.quote("127.0.0.1") + ","
                + Json.quote("dataPort") + ":" + dataPort() + ","
                + Json.quote("controlPort") + ":" + controlPort() + ","
                + Json.quote("dataWorkers") + ":" + config.dataWorkers() + ","
                + Json.quote("controlWorkers") + ":" + config.controlWorkers() + ","
                + Json.quote("catalogResources") + ":" + catalog.size() + ","
                + Json.quote("scenario") + ":" + scenario.toJson()
                + "}";
    }

    private void handleData(HttpExchange exchange) {
        handle(exchange, false);
    }

    private void handleControl(HttpExchange exchange) {
        handle(exchange, true);
    }

    private void handle(HttpExchange exchange, boolean controlPlane) {
        long handlerStartedAt = clock.nowNanos();
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getRawPath();
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        long requestId = requestIds.incrementAndGet();

        addCorrelationHeaders(exchange, requestId, controlPlane);

        RequestTraceAccumulator trace = new RequestTraceAccumulator(
                config,
                scenario,
                requestId,
                handlerStartedAt,
                method,
                rawPath,
                rangeHeader);

        try {
            if (controlPlane) {
                routeControl(exchange, trace, rawPath, method);
            } else {
                routeData(exchange, trace, rawPath, method, rangeHeader);
            }
        } catch (NoProgressCancelledException cancelled) {
            trace.outcome = TraceOutcome.CANCELLED_DURING_NO_PROGRESS;
            System.err.println("media-lab impairment wait cancelled: " + cancelled.getMessage());
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
                RequestTrace completed = trace.complete(clock.nowNanos());
                if (trace.fixtureId != null && completed.serverFirstBodyWriteDelayMs() != null) {
                    calibration.observeFirstBodyDelay(completed.serverFirstBodyWriteDelayMs());
                }
                requestTraceWriter.append(completed);
            } catch (IOException traceFailure) {
                System.err.println("media-lab trace failure: " + traceFailure.getMessage());
            }
        }
    }

    private void routeControl(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            String rawPath,
            String method) throws IOException {

        if ("/__lab/health".equals(rawPath)) {
            if (!"GET".equals(method)) {
                sendMethodNotAllowed(exchange, trace, "GET");
                return;
            }
            sendJson(exchange, trace, 200,
                    "{" + Json.quote("status") + ":" + Json.quote("ok") + ","
                            + Json.quote("sessionId") + ":" + Json.quote(config.sessionId()) + ","
                            + Json.quote("scenarioHash") + ":" + Json.quote(scenario.scenarioHash())
                            + "}");
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

        sendNotFound(exchange, trace);
    }

    private void routeData(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            String rawPath,
            String method,
            String rangeHeader) throws IOException {

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

    private void addCorrelationHeaders(
            HttpExchange exchange,
            long requestId,
            boolean controlPlane) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("X-Sponge-Lab-Session", config.sessionId());
        headers.set("X-Sponge-Lab-Request", Long.toString(requestId));
        headers.set("X-Sponge-Lab-Profile", config.profile().name());
        headers.set("X-Sponge-Lab-Scenario", scenario.scenarioHash());
        headers.set("X-Sponge-Lab-Plane", controlPlane ? "control" : "data");
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

            fixtureBodyWriter.write(input, output, trace, length);
        }
    }

    private void sendJson(
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
            trace.markFirstBodyWrite(clock.nowNanos());
            output.write(body);
            trace.bodyBytesWritten += body.length;
        }
        trace.outcome = TraceOutcome.SUCCESS;
    }

    private void sendNotFound(
            HttpExchange exchange,
            RequestTraceAccumulator trace) throws IOException {
        trace.outcome = TraceOutcome.NOT_FOUND;
        sendErrorJson(exchange, trace, 404, "not_found");
        trace.outcome = TraceOutcome.NOT_FOUND;
    }

    private void sendMethodNotAllowed(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        trace.outcome = TraceOutcome.METHOD_NOT_ALLOWED;
        sendErrorJson(exchange, trace, 405, "method_not_allowed");
        trace.outcome = TraceOutcome.METHOD_NOT_ALLOWED;
    }

    private void sendErrorJson(
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
            trace.markFirstBodyWrite(clock.nowNanos());
            output.write(body);
            trace.bodyBytesWritten += body.length;
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        dataServer.stop(0);
        controlServer.stop(0);

        dataExecutor.shutdown();
        controlExecutor.shutdown();

        IOException failure = null;
        try {
            boolean dataStopped = dataExecutor.awaitTermination(5, TimeUnit.SECONDS);
            boolean controlStopped = controlExecutor.awaitTermination(5, TimeUnit.SECONDS);
            if (!dataStopped) {
                dataExecutor.shutdownNow();
            }
            if (!controlStopped) {
                controlExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            dataExecutor.shutdownNow();
            controlExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            events.complete();
        } catch (IOException exception) {
            failure = exception;
        }

        try {
            CalibrationReportWriter.write(config.calibrationPath(), scenario, calibration);
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }

        try {
            requestTraceWriter.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }

        try {
            eventTraceWriter.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private static void ensureArtifactsAbsent(MediaLabConfig config) throws IOException {
        for (Path path : new Path[] {
                config.tracePath(),
                config.sessionTracePath(),
                config.calibrationPath()
        }) {
            if (Files.exists(path)) {
                throw new IOException("Evidence artifact already exists: " + path);
            }
        }
    }

    private static ExecutorService newFixedThreadPool(int workers, String prefix) {
        AtomicLong workerIds = new AtomicLong();
        return Executors.newFixedThreadPool(
                workers,
                runnable -> {
                    Thread thread = new Thread(runnable, prefix + workerIds.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                });
    }

    private static void closeQuietly(JsonLineTraceWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // Startup failure cleanup only.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Startup failure cleanup only.
        }
    }
}
