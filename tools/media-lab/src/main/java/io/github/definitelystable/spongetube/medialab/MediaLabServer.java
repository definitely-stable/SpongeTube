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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MediaLabServer implements AutoCloseable {

    private static final Pattern PROVIDER_MEDIA_PATH =
            Pattern.compile("^/provider/(gen-[1-9][0-9]*)(/fixtures/.*)$");

    private final MediaLabConfig config;
    private final ResolvedScenario scenario;
    private final MonotonicClock clock;
    private final FixtureCatalog catalog;
    private final JsonLineTraceWriter requestTraceWriter;
    private final JsonLineTraceWriter eventTraceWriter;
    private final JsonLineTraceWriter gateEventTraceWriter;
    private final ManualBodyProgressGate manualBodyProgressGate;
    private final SessionCalibration calibration;
    private final SessionEventRecorder events;
    private final FixtureBodyWriter fixtureBodyWriter;
    private final ProviderSimulator providerSimulator;
    private final ProviderFaultRecorder providerFaultRecorder;
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
            JsonLineTraceWriter gateEventTraceWriter,
            ManualBodyProgressGate manualBodyProgressGate,
            SessionCalibration calibration,
            SessionEventRecorder events,
            FixtureBodyWriter fixtureBodyWriter,
            ProviderSimulator providerSimulator,
            ProviderFaultRecorder providerFaultRecorder,
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
        this.gateEventTraceWriter = gateEventTraceWriter;
        this.manualBodyProgressGate = manualBodyProgressGate;
        this.calibration = calibration;
        this.events = events;
        this.fixtureBodyWriter = fixtureBodyWriter;
        this.providerSimulator = providerSimulator;
        this.providerFaultRecorder = providerFaultRecorder;
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
        JsonLineTraceWriter gateEventTraceWriter = null;
        ProviderFaultRecorder providerFaultRecorder = null;
        ProviderSimulator providerSimulator = null;

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
            if (config.profile() == MediaLabProfile.N4R) {
                gateEventTraceWriter =
                        new JsonLineTraceWriter(config.gateTracePath());
            }
            if (config.profile().isProviderFamily()) {
                providerFaultRecorder = new ProviderFaultRecorder(
                        config.providerFaultsPath(),
                        config.sessionId(),
                        config.sessionId(),
                        config.profile().name(),
                        config.providerVariant().name(),
                        scenario.scenarioHash(),
                        config.providerWallClockEpochMs());
                providerSimulator = new ProviderSimulator(
                        config.providerVariant(),
                        config.providerWallClockEpochMs(),
                        ProviderSimulator.catalogLengths(catalog));
            }

            SessionCalibration calibration = new SessionCalibration();
            SessionEventRecorder events =
                    new SessionEventRecorder(config, scenario, clock, eventTraceWriter);
            ManualBodyProgressGate manualBodyProgressGate =
                    gateEventTraceWriter == null
                            ? null
                            : new ManualBodyProgressGate(clock, gateEventTraceWriter);
            FixtureBodyWriter fixtureBodyWriter =
                    new FixtureBodyWriter(
                            scenario,
                            clock,
                            sleeper,
                            events,
                            calibration,
                            manualBodyProgressGate);

            MediaLabServer mediaLab = new MediaLabServer(
                    config,
                    scenario,
                    clock,
                    catalog,
                    requestTraceWriter,
                    eventTraceWriter,
                    gateEventTraceWriter,
                    manualBodyProgressGate,
                    calibration,
                    events,
                    fixtureBodyWriter,
                    providerSimulator,
                    providerFaultRecorder,
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
            boolean requestTraceCreated = requestTraceWriter != null;
            boolean eventTraceCreated = eventTraceWriter != null;
            boolean gateTraceCreated = gateEventTraceWriter != null;
            boolean providerFaultsCreated = providerFaultRecorder != null;
            closeQuietly(requestTraceWriter);
            closeQuietly(eventTraceWriter);
            closeQuietly(gateEventTraceWriter);
            if (requestTraceCreated) {
                deleteQuietly(config.tracePath());
            }
            if (eventTraceCreated) {
                deleteQuietly(config.sessionTracePath());
            }
            if (gateTraceCreated) {
                deleteQuietly(config.gateTracePath());
            }
            if (providerFaultsCreated) {
                deleteQuietly(config.providerFaultsPath());
            }
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
                + Json.quote("calibration") + ":" + Json.quote(config.calibrationPath().toString()) + ","
                + Json.quote("gateTrace") + ":"
                + (manualBodyProgressGate == null
                        ? "null"
                        : Json.quote(config.gateTracePath().toString()))
                + ","
                + Json.quote("providerVariant") + ":"
                + (config.providerVariant() == null
                        ? "null"
                        : Json.quote(config.providerVariant().name()))
                + ","
                + Json.quote("providerWallClockEpochMs") + ":"
                + (config.providerWallClockEpochMs() == null
                        ? "null"
                        : config.providerWallClockEpochMs())
                + ","
                + Json.quote("providerFaults") + ":"
                + (providerFaultRecorder == null
                        ? "null"
                        : Json.quote(config.providerFaultsPath().toString()))
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
                + ","
                + Json.quote("providerVariant") + ":"
                + (config.providerVariant() == null
                        ? "null"
                        : Json.quote(config.providerVariant().name()))
                + ","
                + Json.quote("providerWallClockEpochMs") + ":"
                + (config.providerWallClockEpochMs() == null
                        ? "null"
                        : config.providerWallClockEpochMs())
                + ","
                + Json.quote("providerFaults") + ":"
                + (providerFaultRecorder == null
                        ? "null"
                        : Json.quote(config.providerFaultsPath().toString()))
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
                controlPlane ? "control" : "data",
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

        if ("/__lab/provider/events".equals(rawPath)) {
            if (!"GET".equals(method)) {
                sendMethodNotAllowed(exchange, trace, "GET");
                return;
            }
            if (providerFaultRecorder == null) {
                sendNotFound(exchange, trace);
                return;
            }
            sendJson(exchange, trace, 200, providerFaultRecorder.json());
            return;
        }

        if (rawPath.startsWith("/__lab/gate/media")) {
            if (manualBodyProgressGate == null) {
                sendErrorJson(exchange, trace, 409, "manual_gate_unavailable");
                trace.outcome = TraceOutcome.SERVER_IO_ERROR;
                return;
            }
            if ("/__lab/gate/media".equals(rawPath)) {
                if (!"GET".equals(method)) {
                    sendMethodNotAllowed(exchange, trace, "GET");
                    return;
                }
                sendJson(exchange, trace, 200, manualBodyProgressGate.stateJson());
                return;
            }
            if (!"POST".equals(method)) {
                sendMethodNotAllowed(exchange, trace, "POST");
                return;
            }
            String commandId =
                    exchange.getRequestHeaders().getFirst("X-Sponge-Gate-Command");
            try {
                String state = switch (rawPath) {
                    case "/__lab/gate/media/close" ->
                            manualBodyProgressGate.close(commandId);
                    case "/__lab/gate/media/open" ->
                            manualBodyProgressGate.open(commandId);
                    default -> null;
                };
                if (state == null) {
                    sendNotFound(exchange, trace);
                } else {
                    sendJson(exchange, trace, 200, state);
                }
            } catch (IllegalArgumentException invalidCommand) {
                sendErrorJson(exchange, trace, 400, "invalid_gate_command");
                trace.outcome = TraceOutcome.SERVER_IO_ERROR;
            }
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

        if (rawPath.startsWith("/provider/")) {
            routeProvider(exchange, trace, rawPath, method, rangeHeader);
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

        serveFixture(exchange, trace, resource, method, rangeHeader);
    }

    private void routeProvider(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            String rawPath,
            String method,
            String rangeHeader) throws IOException {

        if (providerSimulator == null) {
            sendNotFound(exchange, trace);
            return;
        }

        if ("/provider/refresh".equals(rawPath)) {
            routeProviderRefresh(exchange, trace, method);
            return;
        }

        Matcher media = PROVIDER_MEDIA_PATH.matcher(rawPath);
        if (!media.matches()) {
            sendNotFound(exchange, trace);
            return;
        }

        String generation = media.group(1);
        String catalogPath = media.group(2);

        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            sendMethodNotAllowed(exchange, trace, "GET, HEAD");
            return;
        }

        if (!providerSimulator.isIssuedGeneration(generation)) {
            sendNotFound(exchange, trace);
            return;
        }

        FixtureResource resource = catalog.findRawPath(catalogPath);
        if (resource == null) {
            sendNotFound(exchange, trace);
            return;
        }

        trace.fixtureId = resource.fixtureId();
        trace.resourceId = resource.resourceId();

        if ("HEAD".equals(method)) {
            serveFixture(exchange, trace, resource, method, rangeHeader);
            return;
        }

        ProviderSimulator.MediaDecision decision = providerSimulator.mediaGet(
                trace.requestId(),
                generation,
                exchange.getRequestHeaders().getFirst("X-Sponge-Binding-Revision"),
                clock.nowNanos());

        if (!decision.isFault()) {
            serveFixture(exchange, trace, resource, method, rangeHeader);
            return;
        }

        providerFaultRecorder.append(decision.faultEvent());
        if (decision.retryAfterHeader() != null) {
            exchange.getResponseHeaders().set("Retry-After", decision.retryAfterHeader());
        }
        if (decision.staleBindingHeader()) {
            exchange.getResponseHeaders().set("X-Sponge-Provider-Binding", "STALE");
        }
        sendErrorJson(exchange, trace, decision.statusCode(), decision.errorCode());
        trace.outcome = TraceOutcome.PROVIDER_FAULT;
    }

    private void routeProviderRefresh(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            String method) throws IOException {

        if (providerSimulator == null || !providerSimulator.supportsRefresh()) {
            sendNotFound(exchange, trace);
            return;
        }

        if (!"POST".equals(method)) {
            sendMethodNotAllowed(exchange, trace, "POST");
            return;
        }

        ProviderSimulator.RefreshDecision decision = providerSimulator.refresh(
                trace.requestId(),
                exchange.getRequestHeaders().getFirst("X-Sponge-Provider-Generation"),
                exchange.getRequestHeaders().getFirst("X-Sponge-Binding-Revision"),
                clock.nowNanos());

        providerFaultRecorder.append(decision.faultEvent());
        sendJson(exchange, trace, decision.statusCode(), decision.bodyJson());
        if (!decision.successful()) {
            trace.outcome = TraceOutcome.PROVIDER_FAULT;
        }
    }

    private void serveFixture(
            HttpExchange exchange,
            RequestTraceAccumulator trace,
            FixtureResource resource,
            String method,
            String rangeHeader) throws IOException {

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

        if (gateEventTraceWriter != null) {
            try {
                gateEventTraceWriter.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
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
                config.calibrationPath(),
                config.gateTracePath(),
                config.providerFaultsPath()
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
