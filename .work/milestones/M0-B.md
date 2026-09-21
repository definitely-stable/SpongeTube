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

### 2.6 Port model

The host binds 127.0.0.1:0 by default and prints its selected port in a machine-readable READY record.

The orchestrator maps a stable Android port:

    adb -s <serial> reverse tcp:18080 tcp:<hostPort>

App/test URL:

    http://localhost:18080/

This works for both emulator and USB-connected physical devices.

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
- JUnit 6.1.2 for tests;
- JDK HttpClient for server integration tests.

It has no Android or Media3 dependency.

## 5. Process contract

Suggested CLI:

    media-lab serve
      --fixture-root=<path>
      --profile=N0|N1|N4
      --session-id=<id>
      --trace=<path>
      --port=0
      --workers=8

Invalid configuration fails before bind.

The first stdout record is machine-readable READY JSON containing schemaVersion, host, selected port, sessionId and profileId. Human logs go to stderr.

## 6. Server execution model

Bind:
- IPv4 loopback only;
- never 0.0.0.0 by default.

Executor:
- explicit fixed/bounded worker pool;
- provisional workers = 8;
- enough for concurrent A/V plus control traffic;
- effective value appears in startup config.

Responses:
- fixed content length whenever known;
- no chunked transfer for fixture bodies;
- all exchange streams closed;
- client disconnect recorded explicitly.

## 7. HTTP surface

Control endpoints:

    GET /__lab/health
    GET /__lab/config

Control endpoints are never subject to N1/N4 body impairment.

Fixture endpoints:

    GET  /fixtures/<fixture-id>/<resource>
    HEAD /fixtures/<fixture-id>/<resource>

Other methods:

    405 Method Not Allowed
    Allow: GET, HEAD

Fixture headers include:
- Accept-Ranges: bytes
- Cache-Control: no-store
- exact Content-Length

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

Later Android timestamps belong to a different clock domain. Host and Android monotonic timestamps must never be subtracted directly.

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
- acceptedAtMonotonicNs
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

M0-B proves that the server follows its configured schedule.

M0-F later calibrates observed real-time behavior before using the lab for acceptance comparisons.

Expose enough evidence for calibration:
- configured delay/rate/no-progress;
- server write timings;
- body bytes written.

Never label a configured N1 value as measured real network throughput.

Record both `referencePlaybackBitrateBps`, `aggregateRateRatio` and resolved `aggregateRateBps` in scenario/startup evidence so future comparisons remain interpretable.

## 23. Delivery plan

M0-B is intentionally split into three focused PRs.

### B1 — HTTP origin kernel

Title:
test(media-lab): add deterministic HTTP origin kernel

Deliver:
- tools:media-lab module;
- Java application/test toolchain;
- CLI/process contract;
- loopback/port 0;
- explicit executor/shutdown;
- fixture catalog;
- GET/HEAD;
- Range contract;
- health/config;
- JSONL trace foundation;
- host tests.

No long-form committed fixture yet.

### B2 — impairment engine

Title:
test(media-lab): add deterministic impairment profiles

Deliver:
- injected clock/sleeper;
- shared GlobalBandwidthGovernor;
- shared NoProgressGate;
- N0/N1/N4 immutable specs with N1 resolved from a ratio against fixture reference bitrate;
- provisional 8 KiB quantum;
- fake-clock tests;
- short socket-level smoke.

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
- actual size/bitrate metadata;
- adb reverse helper/docs;
- debug/lab-only localhost cleartext support needed by M0-C;
- end-to-end host smoke using committed fixtures.

## 24. Confirmed failure risks

R1: per-response pacing multiplies throughput with separate A/V.
Mitigation: one shared governor.

R2: N4 affects only one track/request.
Mitigation: one shared gate.

R3: N4 blocks control endpoints.
Mitigation: control path bypasses fixture impairment.

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

## 25. Definition of Done

M0-B/#5 is complete only when:
- [ ] tools:media-lab is the only new Gradle module.
- [ ] no third-party runtime dependency is used by the lab.
- [ ] server binds loopback only and supports port 0.
- [ ] READY/config output is machine-readable.
- [ ] explicit executor and clean shutdown are tested.
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
- [ ] F0 is committed, hashed and Range-tested.
- [ ] F1 contains exactly one video and one audio representation.
- [ ] F1 records provenance, actual bytes and actual bitrate.
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
- JUnit 6.1.2 release notes: https://docs.junit.org/6.1.2/release-notes.html
