# M0-B — Deterministic VOD Media Lab

Status: **Accepted implementation plan**
Parent milestone: .work/milestones/M0.md
Tracking issue: **#5**
Date: **2026-09-21**

## 1. Purpose

M0-B builds a deterministic local HTTP media origin and synthetic VOD fixture set for later Media3/Sponge comparisons.

It is deliberately not a production backend, YouTube emulator, faithful TCP/QUIC/VPN simulator, or benchmark result by itself.

The primary invariant is reproducibility, not realism at every layer.

## 2. Audit conclusions

### 2.1 Keep the JDK HTTP server

Use Java 17 jdk.httpserver.HttpServer.

Reasons:
- no runtime networking dependency;
- sufficient HTTP/1.1 request/response control;
- explicit executor selection;
- fixed response lengths;
- direct response-body pacing/pause control;
- simple host integration tests.

Media3 itself uses layered fake-data and HTTP-server tests. SpongeTube follows the same testing principle but keeps the runnable origin independent of the transport library later under evaluation.

The server must not be used to infer HTTP/2, HTTP/3 or QUIC performance.

### 2.2 Bandwidth is session-global

N1 bandwidth control is shared by all concurrent fixture responses.

    video response --+
                     +--> GlobalBandwidthGovernor --> 0.5 × F1 reference playback bitrate
    audio response --+

A per-response limiter would incorrectly multiply available bandwidth when audio and video are fetched concurrently.

### 2.3 N4 is a shared fixture-body gate

Canonical M0 semantics:

    normal fixture delivery
      -> global no-progress window
      -> all in-flight fixture bodies stop new body writes
      -> new fixture bodies wait
      -> 120 seconds
      -> delivery resumes

Lab-control endpoints stay responsive.

This is application-layer no-progress, not HTTP 503, socket reset, DNS failure, route loss or VPN failure. OS/TCP buffers can still drain bytes written before the gate closes, so the lab uses bounded writes and deliberate flushing and documents this limitation.

### 2.4 N1 delay is not RTT

Reject the phrase "120 ms RTT-equivalent".

M0-B defines firstBodyDelayMs = 120. It is server-side delay before fixture body progress.

### 2.5 One process equals one scenario session

No mutable HTTP control plane in M0-B.

Fixture root, profile, session id, trace path, port and worker count are immutable for the process lifetime. Restarting the process resets all scenario state.

### 2.6 Data/control plane isolation

The Media Lab exposes two independent IPv4-loopback listeners with independent bounded executors:

    DataServer
      127.0.0.1:<dynamic-data-port>
      /fixtures/...

    ControlServer
      127.0.0.1:<dynamic-control-port>
      /__lab/health
      /__lab/config

Both ports default to 0 and are reported in the machine-readable READY record.

This is a correctness property, not only an optimization. B2 N4 may deliberately block every media-body worker; health/config must still be schedulable because the control plane does not share the data executor.

The orchestrator maps only the data listener to Android:

    adb -s <serial> reverse tcp:18080 tcp:<dataPort>

App/test media URL:

    http://localhost:18080/

The control listener remains host-local and is used by the orchestrator/CI only.

`adb reverse` is valid for deterministic media-delivery tests on emulator and USB-connected devices. It is **not** evidence for Android VPN/default-route semantics because it changes the network path. M2 route/VPN scenarios must use a test path that actually traverses Android's selected default network.

### 2.7 Cleartext is lab/debug only

Production/default app configuration remains cleartext-off. Any localhost HTTP exception is source-set scoped to the lab/debug build.

### 2.8 Do not serve arbitrary host files

The server builds an immutable fixture catalog at startup.

Rules:
- only regular files under test-fixtures/media are cataloged;
- symlinks are rejected;
- no directory listing;
- unknown resources are 404;
- traversal and encoded traversal are rejected;
- requests never resolve arbitrary user paths directly against the filesystem.

## 3. Repository shape

M0-B adds:

    tools/media-lab/
      build.gradle.kts
      src/main/java/...
      src/test/java/...

    test-fixtures/media/
      manifest.json
      checksums.sha256
      GENERATION.md
      F0/progressive.mp4
      F1/...

    scripts/media-lab/
      adb-reverse.sh or equivalent minimal helper

Gradle project graph after #5:

    :app
    :tools:media-lab

No playback:baseline, benchmark or shared build-logic module is added by M0-B.

## 4. Module technology

tools:media-lab is a host JVM application:
- Java 17 toolchain;
- Gradle application plugin;
- JDK jdk.httpserver;
- no third-party runtime dependency;
- JUnit 6.1.3 for tests;
- JDK HttpClient for server integration tests.

It has no Android or Media3 dependency.

## 5. Process contract

Suggested CLI:

    media-lab serve
      --fixture-root=<path>
      --profile=N0|N1|N4
      --session-id=<id>
      --trace=<path>
      --data-port=0
      --data-workers=8
      --control-port=0
      --control-workers=2
      --write-quantum-bytes=8192
      [--reference-playback-bitrate-bps=<bps>]   # required by N1
      [--no-progress-start-after-ms=<ms>]        # required by N4

Invalid configuration fails before bind.

N1 has no hidden fixture bitrate: the caller supplies the F1 reference playback bitrate resolved from the committed B3 fixture manifest. N4 has no hidden start offset: the caller supplies it explicitly; canonical duration remains 120000 ms.

Both listeners are bound before evidence files are created. If either bind fails, startup fails without leaving an empty session artifact.

The --trace path is the request-trace base name. B2 derives sibling evidence files:
- <trace-stem>.events<ext> for session events;
- <trace-stem>.calibration<ext> for configured-vs-observed calibration.

The first stdout record is machine-readable READY JSON containing at least schemaVersion, host, dataPort, controlPort, sessionId, profileId, scenarioId, scenarioHash, effective worker counts and evidence paths. Human logs go to stderr.

## 6. Server execution model

Bind:
- two IPv4-loopback listeners only;
- never 0.0.0.0 by default;
- data and control ports are distinct when explicitly configured.

Data executor:
- explicit fixed/bounded worker pool;
- provisional workers = 8;
- owns fixture delivery only.

Control executor:
- separate explicit fixed/bounded worker pool;
- provisional workers = 2;
- owns health/config only;
- must never execute fixture-body impairment waits.

Effective values appear in startup/config output.

Responses:
- fixed content length whenever known;
- no chunked transfer for fixture bodies;
- all exchange streams closed;
- client disconnect recorded explicitly.

The B2 responsiveness gate is stronger than "control code bypasses impairment": with canonical F1 A/V concurrency, N4 must be able to occupy/block data workers while a control request still completes promptly on the independent listener/executor.

## 7. HTTP surface

Control listener only:

    GET /__lab/health
    GET /__lab/config

Data listener only:

    GET  /fixtures/<fixture-id>/<resource>
    HEAD /fixtures/<fixture-id>/<resource>

The opposite-plane path is 404. Control endpoints are never subject to N1/N4 body impairment.

Other fixture methods:

    405 Method Not Allowed
    Allow: GET, HEAD

Fixture headers include:
- Accept-Ranges: bytes
- Cache-Control: no-store
- exact Content-Length
- X-Sponge-Lab-Session
- X-Sponge-Lab-Request
- X-Sponge-Lab-Profile
- X-Sponge-Lab-Plane

The lab-only correlation headers allow the Android measurement harness to join a client observation to a host request without subtracting timestamps from different monotonic clock domains.

No dynamic gzip compression.

## 8. HTTP Range contract

Follow RFC 9110 for the supported single-byte-range subset.

Supported:
- bytes=0-99
- bytes=100-
- bytes=-500

Satisfiable range:
- 206 Partial Content
- Content-Range: bytes start-end/completeLength
- Content-Length equals selected length.

End positions beyond EOF are clamped to the final byte.

A suffix larger than the whole resource selects the whole representation as a partial response.

Valid but unsatisfiable range:
- 416 Range Not Satisfiable
- Content-Range: bytes */completeLength

Examples include a first byte position at or beyond a non-empty representation length and suffix length zero.

An integer range whose last position is lower than its first position (for example `bytes=20-10`) is **invalid syntax**, not valid-but-unsatisfiable. M0-B deterministically ignores it and returns the normal 200 representation.

Malformed or multiple-range syntax is not implemented as multipart. The lab deterministically ignores unsupported/malformed Range and returns the normal 200 representation. It must not misuse 416 merely because multipart support is absent.

HEAD returns full-representation headers and no body.

## 9. Fixture body pipeline

All fixture bodies use one path:

    handler
      -> FixtureBodyWriter
      -> FirstBodyDelay
      -> NoProgressGate
      -> GlobalBandwidthGovernor
      -> socket OutputStream

No fixture handler bypasses it.

Provisional write quantum: 8 KiB.

The exact quantum is configuration/evidence data, not a product invariant.

## 10. Profiles

### N0 — CONTROL

    firstBodyDelayMs = 0
    aggregateRateBps = unlimited
    noProgress = none

Meaning: no artificial application-layer impairment.

### N1 — SLOW

Canonical M0-B N1 is relative to the actual committed F1 media demand:

    firstBodyDelayMs = 120
    aggregateRateRatio = 0.50
    aggregateRateBps = round(referencePlaybackBitrateBps × 0.50)
    noProgress = none
    chunkBytes = 8192

Where:

    referencePlaybackBitrateBps =
      actualVideoAverageBitrateBps
      + actualAudioAverageBitrateBps

The actual averages are derived from committed F1 bytes and duration, not only from encoder target values or MPD advertised bandwidth.

The resulting rate is aggregate across simultaneous A/V responses.

This makes N1 deliberately under-provisioned at 50% of the fixture's required playback bitrate while remaining stable if the exact F1 bitrate changes during fixture generation.

The lab impairment engine must therefore support a ratio-based rate specification resolved from fixture metadata. Tests for the governor may also use an explicit absolute rate for low-level deterministic unit cases.

### N4 — LONG_NO_PROGRESS

    firstBodyDelayMs = 0
    aggregateRateBps = unlimited
    noProgressStartAfterMs = explicit configuration
    noProgressDurationMs = 120_000

During the window:
- every fixture body blocks before its next write;
- new fixture bodies also wait;
- control endpoints remain responsive;
- client cancellation is allowed and traced.

The canonical M0-F run later fixes the start offset rather than hiding it in server code.

## 11. Global bandwidth governor

One session owns one governor.

Reservation model:

    reserve(bytes):
      fair lock
      slotStart = max(now, nextAvailable)
      slotDuration = bytes * 8 / rate
      nextAvailable = slotStart + slotDuration
      unlock
      sleepUntil(slotStart)

Properties:
- shared by audio and video;
- monotonic clock;
- bounded reservations;
- absolute deadlines avoid cumulative sleep drift;
- fake clock/sleeper in unit tests.

## 12. No-progress gate

One session owns one gate.

Its time origin is first fixture-body progress in the session.

Every fixture-body write checks the gate.

Tests use fake time; no unit test waits 120 real seconds.

## 13. Clock rules

Server duration math uses System.nanoTime() or injected monotonic clock.

Later Android timestamps belong to a different clock domain. Host and Android monotonic timestamps must never be subtracted directly. `handlerStartedAtMonotonicNs` is the JDK handler-entry timestamp; the lab does not claim access to the underlying socket-accept timestamp.

B2 deterministic impairment tests use an injected monotonic clock/sleeper, following the same principle as Media3's FakeClock: timed state transitions are advanced explicitly instead of making PR tests sleep in real time.

A future provider-expiry simulator may add a separate VirtualWallClock for signed-URL/descriptor expiry and Retry-After semantics. It must never replace or be mixed with the monotonic clock used for durations, pacing and deadlines.

## 14. Request trace schema v1

One completed request produces one JSONL record.

Required fields:
- schemaVersion
- sessionId
- requestId
- fixtureId / resourceId where applicable
- profileId
- method
- path
- raw range header
- resolved start/end-exclusive
- status
- plannedResponseBytes
- bodyBytesWritten
- handlerStartedAtMonotonicNs
- firstBodyWriteAtMonotonicNs
- completedAtMonotonicNs
- serverFirstBodyWriteDelayMs
- handlerDurationMs
- configuredRateBps
- noProgressWaitMs
- outcome

Outcomes include:
- SUCCESS
- NOT_FOUND
- METHOD_NOT_ALLOWED
- RANGE_UNSATISFIABLE
- CLIENT_DISCONNECTED
- SERVER_IO_ERROR
- CANCELLED_DURING_NO_PROGRESS

bodyBytesWritten means writes completed by the handler, not exact remote-NIC wire bytes.

JSON escaping is unit-tested. Appends are serialized/thread-safe and each complete record is flushed as one line.

### 14.1 Session event trace

B2 adds a small session-event stream beside per-request JSONL records. It records scenario-level transitions that cannot be reconstructed reliably from completed request rows alone:

- SESSION_STARTED
- N1_RATE_RESOLVED
- FIRST_MEDIA_PROGRESS
- NO_PROGRESS_WINDOW_SCHEDULED
- NO_PROGRESS_WINDOW_ENTERED
- NO_PROGRESS_WINDOW_EXITED
- SESSION_COMPLETED

Each event carries schemaVersion, sessionId, scenarioId/scenarioHash once available, a host-monotonic timestamp and event-specific fields.

### 14.2 Resolved scenario identity

A profile label such as N1 is not sufficient benchmark identity. B2 resolves immutable inputs into a canonical ResolvedScenario containing at least:

- schemaVersion
- scenarioId
- profileId
- firstBodyDelayMs
- aggregateRateRatio where applicable
- resolved aggregateRateBps
- writeQuantumBytes
- noProgressStartAfterMs
- noProgressDurationMs
- random seed when a future stochastic layer is used

`scenarioHash = SHA-256(canonical resolved scenario bytes)`.

The fixture identity remains separately hashed. A run is comparable only when the relevant scenario/fixture identities match, or the report explicitly treats the difference as the variable under test.

The B2 scenario hash is returned in config/startup evidence and later in `X-Sponge-Lab-Scenario`.

## 15. Fixture strategy

All committed fixtures are synthetic/project-generated.

### F0 — progressive smoke

Purpose:
- 200 response;
- Range correctness;
- basic progressive seek/read.

Target:
- about 10 seconds;
- MP4;
- AVC;
- AAC optional;
- <= 2 MiB;
- faststart.

### F1 — long-form separate A/V DASH

Purpose:
- separate A/V requests;
- Range-heavy access;
- enough duration for N4.

Preferred target:
- about 180 seconds;
- AVC video, one representation;
- AAC-LC audio, one representation;
- fMP4 static DASH;
- no adaptive alternatives.

Prefer single-file/range-addressable separate A/V DASH if Media3 compatibility is clean. This better matches future separate-track/range workloads. If the generator's single-file DASH form is less stable, use explicit fMP4 segments and record that decision.

The fixture shape is validated during M0-B and then frozen; it must not silently change later.

Initial binary budget:
- F0 + F1 <= about 40 MiB.
- No Git LFS unless this budget proves unsustainable.

## 16. Fixture generation and provenance

Normal CI never regenerates fixture media. Committed bytes are canonical and CI verifies their hashes.

Record:
- generator and exact version;
- exact commands;
- relevant encoder/build information;
- duration;
- actual bytes;
- measured average bitrate;
- codec/profile;
- SHA-256 per payload.

Recommended current generator baseline: FFmpeg 9.0.2, released 2026-09-18.

Synthetic video/audio sources avoid external media licensing dependencies.

Byte-identical output from arbitrary FFmpeg builds is not required; the committed hashes define canonical bytes.

## 17. Fixture metadata

test-fixtures/media/manifest.json contains descriptive metadata.

Per fixture:
- schemaVersion
- fixtureId
- kind
- durationMs
- codecs
- container
- actualBytes
- actualAverageBitrateBps
- referencePlaybackBitrateBps for fixtures with required separate A/V tracks
- generator/version
- generation recipe
- resource list

Per resource:
- relativePath
- role
- sizeBytes
- sha256
- contentType

checksums.sha256 independently lists canonical payload checksums.

The runtime server does not need to add a full JSON library merely to serve these files.

### Fixture validation levels

B3 treats a frozen fixture as more than a byte blob:

1. byte identity — committed SHA-256;
2. structural metadata — ffprobe (and another container-level inspector only if a concrete ambiguity remains);
3. DASH/CMAF conformance — DASH-IF Conformance on fixture-generation/change workflows.

Normal PR CI verifies hashes/manifest consistency and does not rerun heavyweight conformance when fixture bytes are unchanged. A PR that changes F1 media or its MPD must attach conformance evidence.

F2/F3 diagnostic packaging variants are deliberately deferred until a real ambiguity appears (for example, segmented-vs-range-addressable behavior). They are not added to the initial 40 MiB corpus merely for breadth.

## 18. Catalog safety

At startup:
1. resolve fixture root;
2. reject symlinks;
3. enumerate regular files;
4. create immutable URL-to-file mapping;
5. reject normalized collisions.

Fixture paths use a conservative ASCII subset. Encoded path tricks are rejected instead of decoded into arbitrary filesystem paths.

## 19. Android localhost bridge

Use adb reverse, not hard-coded emulator address 10.0.2.2.

Benefits:
- same URL on emulator and real device;
- server stays host-loopback only;
- no emulator-specific address in product code;
- parallel devices can each map their own tcp:18080.

M0-B documents/provides the helper; M0-C consumes it.

## 20. Debug-only cleartext

targetSdk 36 disables ordinary cleartext by default on modern Android.

Any localhost HTTP allowance belongs only in debug/lab source-set configuration. Release/main product policy remains unchanged.

## 21. Test architecture

### Pure host tests

RangeParser:
- no Range;
- 0-0;
- bounded;
- open-ended;
- suffix;
- oversize suffix;
- end beyond EOF;
- start at/beyond EOF;
- start > end;
- malformed;
- multi-range.

Bandwidth governor with fake clock:
- aggregate reservation;
- concurrent logical streams share one budget;
- deadline math;
- unlimited mode;
- invalid config.

NoProgressGate with fake clock:
- before/start/inside/end/after window;
- interruption/cancellation.

Trace encoder:
- escaping;
- optional fields;
- complete one-line record;
- concurrent serialization.

Fixture catalog:
- 404;
- traversal;
- encoded traversal;
- symlink rejection;
- duplicate/collision cases.

### Real loopback HTTP integration

Use JDK HttpClient against server on port 0.

Verify:
- health/config;
- GET 200;
- HEAD;
- 206 headers/body;
- 416;
- 404;
- 405;
- fixed-length response;
- trace output;
- client abort where practical.

### Short impairment smoke

Use test-only short delay/no-progress/rate values. Canonical N4 remains 120 seconds; PR tests must not wait 120 seconds.

### Fixture verification

check verifies:
- listed payloads exist;
- sizes match;
- SHA-256 matches;
- manifest/checksum consistency;
- unexpected payloads are detected.

CI verifies fixture bytes; it does not regenerate them.

## 22. Calibration boundary

M0-B proves that the server follows its configured schedule. A configured value is not accepted as an observed value merely because the code requested it.

M0-F later calibrates real-time behavior before using the lab for acceptance comparisons.

Expose enough evidence for calibration:
- configured delay/rate/no-progress;
- observed server write timings;
- body bytes written;
- resolved scenario identity;
- session transition events.

Derived harness-accuracy metrics include:

    observedRateBps
    rateErrorPct
    observedFirstBodyDelayMs
    firstBodyDelayErrorMs
    observedNoProgressDurationMs
    noProgressDurationErrorMs
    maxSchedulerSlipMs

No permanent pass/fail percentage is invented before pilot runs characterize host/CI jitter. Initial calibration evidence reports raw error distributions; a tolerance becomes a gate only after evidence supports it.

Socket/kernel buffering means "server stopped writing" is not identical to "client instantly received zero additional bytes". B2 includes a short real-socket calibration that characterizes bounded post-gate drain/leakage on the host path and records the limitation. Android acceptance reports app-visible no-progress separately rather than pretending host and device clocks are synchronized.

Never label a configured N1 value as measured real network throughput.

Record `referencePlaybackBitrateBps`, `aggregateRateRatio`, resolved `aggregateRateBps`, scenarioHash and fixture identity in run evidence so future comparisons remain interpretable.

## 23. Delivery plan

M0-B is intentionally split into three focused PRs.

### B1 — HTTP origin kernel

Title:
test(media-lab): add deterministic HTTP origin kernel

Deliver:
- tools:media-lab module;
- Java application/test toolchain;
- CLI/process contract;
- independent data/control loopback listeners on port 0;
- independent bounded executors and clean shutdown;
- fixture catalog;
- GET/HEAD;
- Range contract;
- host-only health/config;
- lab request/session/profile/plane correlation headers;
- JSONL trace foundation;
- host tests including partial-bind cleanup and plane isolation.

No impairment engine and no long-form committed fixture yet.

### B2 — impairment engine

Title:
test(media-lab): add deterministic impairment profiles

Deliver:
- injected monotonic clock/sleeper;
- shared GlobalBandwidthGovernor;
- shared NoProgressGate;
- N0/N1/N4 immutable ResolvedScenario specs with canonical scenarioHash;
- N1 resolved from a ratio against fixture reference bitrate;
- provisional 8 KiB quantum;
- session event trace;
- configured-vs-observed calibration summary;
- fake-clock tests;
- short socket-level smoke including control responsiveness and post-gate drain characterization.

### B3 — canonical fixtures and Android bridge

Title:
test(fixtures): add canonical VOD media lab fixtures

Deliver:
- F0;
- F1;
- FFmpeg provenance/generation recipe;
- manifest.json;
- checksums.sha256;
- fixture verification;
- ffprobe structural evidence;
- DASH-IF conformance evidence when DASH fixture bytes/MPD are introduced or changed;
- actual size/bitrate metadata;
- adb reverse helper/docs;
- explicit statement that adb reverse is not used to validate VPN/default-route semantics;
- debug/lab-only localhost cleartext support needed by M0-C;
- end-to-end host smoke using committed fixtures.

## 24. Confirmed failure risks

R1: per-response pacing multiplies throughput with separate A/V.
Mitigation: one shared governor.

R2: N4 affects only one track/request.
Mitigation: one shared gate.

R3: N4 blocks control endpoints.
Mitigation: data/control listeners and executors are physically independent; B2 real-socket smoke proves control completion while media remains blocked.

R4: broken Range semantics poison seek/cache experiments.
Mitigation: RFC table tests + loopback integration.

R5: generic file server exposes host files.
Mitigation: immutable startup catalog; reject symlinks/traversal.

R6: concurrent A/V makes N1 variable.
Mitigation: aggregate reservations against one monotonic schedule.

R7: socket buffering leaks a small amount of already-written data after N4 starts.
Mitigation: bounded writes/flush; precise documented semantics; M0-F calibration.

R8: fixture generator changes output.
Mitigation: committed hashes are canonical.

R9: synthetic media compresses much lower than requested.
Mitigation: record actual bytes/bitrate and review before freezing fixture.

R10: adaptive track selection changes request volume.
Mitigation: exactly one video and one audio representation.

R11: localhost cleartext leaks to release.
Mitigation: debug/lab source-set only.

R12: fixture bytes permanently bloat Git.
Mitigation: reviewed initial budget around 40 MiB.

R13: N4 media waits consume the same executor as health/config and make the control plane appear dead.
Mitigation: physically separate data/control listeners and executors; B2 tests canonical F1 saturation plus control responsiveness.

R14: a profile label such as N1 hides resolved parameters and makes historical benchmark runs incomparable.
Mitigation: canonical ResolvedScenario + scenarioHash; fixture identity remains separately hashed.

R15: configured impairment is mistaken for delivered impairment.
Mitigation: calibration records configured-vs-observed error and scheduler slip before benchmark conclusions are accepted.

R16: Android and host events are correlated by timestamps from unrelated monotonic clocks.
Mitigation: stable request/session correlation headers and IDs; never subtract cross-domain timestamps.

R17: a byte-identical but malformed DASH fixture poisons playback conclusions.
Mitigation: fixture generation/change workflow includes structural inspection and DASH-IF conformance evidence.

## 25. Definition of Done

M0-B/#5 is complete only when:
- [ ] tools:media-lab is the only new Gradle module.
- [ ] no third-party runtime dependency is used by the lab.
- [ ] data and control servers bind loopback only and support independent port 0 allocation.
- [ ] READY/config output is machine-readable and reports both ports/worker counts.
- [ ] data/control executors are independent; partial bind failures leave no trace artifact.
- [ ] clean shutdown is tested.
- [ ] only cataloged resources are served.
- [ ] GET/HEAD/404/405 are tested.
- [ ] 206 single-range semantics are tested.
- [ ] 416 includes Content-Range: bytes */length.
- [ ] malformed/multi-range behavior is deterministic.
- [ ] N1 uses one aggregate bandwidth governor and resolves canonical rate as 0.50 × F1 reference playback bitrate.
- [ ] N1 evidence records reference playback bitrate, 0.50 ratio, resolved aggregate rate, and 120 ms first-body delay; the delay is never described as RTT.
- [ ] N4 uses one session-wide no-progress gate.
- [ ] N4 duration is 120000 ms.
- [ ] unit tests never wait 120 real seconds.
- [ ] trace schema v1 is committed and thread-safe.
- [ ] lab response correlation headers expose session/request/profile/plane; B2 adds scenarioHash.
- [ ] B2 emits session-level scenario transition events.
- [ ] resolved scenario identity is canonical and hashed.
- [ ] configured-vs-observed delivery calibration is retained before M0-F comparisons.
- [ ] F0 is committed, hashed and Range-tested.
- [ ] F1 contains exactly one video and one audio representation.
- [ ] F1 records provenance, actual bytes and actual bitrate.
- [ ] F1 DASH structure/conformance evidence exists for the committed bytes/MPD.
- [ ] fixture checksums are part of check.
- [ ] fixture size remains within reviewed budget.
- [ ] adb reverse path is documented for emulator and physical device.
- [ ] cleartext allowance is debug/lab-only.
- [ ] no YouTube/SABR/Media3 playback/Sponge Core implementation enters #5.
- [ ] #5 contains final evidence links before closure.

## 26. References

- RFC 9110: https://www.rfc-editor.org/rfc/rfc9110.html
- Java 17 HttpServer: https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html
- Java 17 HttpExchange: https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpExchange.html
- Android adb reverse guidance: https://developer.android.com/develop/ui/views/layout/webapps/access-local-server
- Android Network Security Configuration: https://developer.android.com/privacy-and-security/security-config
- Android Emulator networking: https://developer.android.com/studio/run/emulator-networking-address
- AndroidX Media: https://github.com/androidx/media
- FFmpeg filters: https://ffmpeg.org/ffmpeg-filters.html
- FFmpeg formats/DASH: https://ffmpeg.org/ffmpeg-formats.html
- FFmpeg releases: https://ffmpeg.org/download.html
- JUnit 6.1.3 release notes: https://docs.junit.org/6.1.3/release-notes.html
- Media3 FakeClock: https://developer.android.com/reference/androidx/media3/test/utils/FakeClock
- GStreamer Validate scenarios: https://gstreamer.freedesktop.org/documentation/gst-devtools/gst-validate-scenarios.html
- DASH-IF Conformance: https://github.com/Dash-Industry-Forum/DASH-IF-Conformance
- Toxiproxy (future M2 transport-fault layer): https://github.com/Shopify/toxiproxy
- Linux tc-netem (future M2 packet/network layer): https://man7.org/linux/man-pages/man8/tc-netem.8.html
- Android Macrobenchmark: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- Perfetto Trace Summarization: https://perfetto.dev/docs/analysis/trace-summary
