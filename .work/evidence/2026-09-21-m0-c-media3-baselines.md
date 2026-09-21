# M0-C Media3 Baseline Evidence — 2026-09-21

Status: **Correctness evidence**
Issue: #6
PR: #26

## Question

Do the frozen non-Sponge Media3 reference paths behave correctly against the canonical F1 fixture before M0-D starts measuring them?

## Hypothesis

1. DIRECT can prepare/play/pause/resume/seek the canonical separate-A/V DASH fixture through the recommended platform transport.
2. STANDARD_CACHE COLD starts with zero retained coverage and persists playback bytes.
3. STANDARD_CACHE WARM can replay a defined retained prefix after the Media Lab origin is stopped.
4. An uncached DIRECT request against the stopped origin produces an explicit Media3 network error instead of an app crash.
5. The host trace proves separate video/audio DASH fetches without requiring cross-clock subtraction.

## Build / run identity

- PR head exercised: `afa9aec7c54a3e9a658b6d522e38616f5c455c53`
- Bootstrap Verify: run #113 — SUCCESS
- Android smoke: run #9 / run id `35594405738` — SUCCESS
- smoke artifact id: `10635633330`
- artifact digest: `sha256:c3a1507a69d4e32c3b8c8862e29b5ab145c6b91193b01d3514f4ba0f6649ccf9`
- artifact retention for this CI run: 14 days
- Media3: 1.11.1
- build subject: debug correctness shell; **not** representative performance evidence

## Device / runtime

- emulator API: 36
- build fingerprint:
  `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`
- `adb reverse`: Android localhost:18080 -> Media Lab data listener 127.0.0.1:18081
- Media Lab control listener remained host-only on 127.0.0.1:18082

This emulator result establishes correctness/API compatibility only.

## Fixture identity

Canonical F1 from M0-B3:

- static segmented fMP4 DASH
- duration: 180 s
- exactly one H.264 Main video representation
- exactly one AAC-LC audio representation
- referencePlaybackBitrateBps: 620,180
- MPD SHA-256:
  `e0e05820165ae7c93c81ee8a71a4a3c1412bf6262416dcb1709c769caee07a56`

## Scenario identity

Media Lab N0:

- sessionId: `m0-c-api36`
- scenarioId: `N0`
- scenarioHash:
  `b1f472ab6fcd2b029de8fd0342f6a87eba1b1ae35f3bddc39876190e79beef0f`
- configured first-body delay: 0 ms
- configured aggregate rate: unlimited
- configured no-progress window: none
- observed first-body delay in lab calibration: 0 ms
- max scheduler slip: 0 ms

Host and Android monotonic timestamps remain separate domains. No host/device timestamp subtraction is used.

## Procedure

1. Boot a clean API 36 emulator.
2. Install the debug correctness shell.
3. Start canonical Media Lab N0 and bind only the data listener through `adb reverse`.
4. Run DIRECT using `RECOMMENDED_PLATFORM`; assert effective transport = `HTTP_ENGINE`.
5. Exercise play, pause/resume, forward seek and backward seek.
6. Run STANDARD_CACHE COLD using the same `RECOMMENDED_PLATFORM`; assert effective transport = `HTTP_ENGINE` and cache starts empty.
7. Verify retained cache bytes and separate video/audio requests in the host trace.
8. Stop the Media Lab origin.
9. Force-stop/recreate the app process.
10. Run STANDARD_CACHE WARM using `RECOMMENDED_PLATFORM`; require playback progress from persisted retained coverage while origin is unavailable.
11. Run DIRECT/DEFAULT_HTTP against the still-stopped origin; require an explicit Media3 playback error.

## Raw observations

### DIRECT

```text
status=PASS
effective=HTTP_ENGINE
beforeForward=1503 ms
afterForward=5957 ms
afterBackward=2663 ms
```

The run exercised play, pause/resume, a forward seek to 5 s and a backward seek to 1 s.

### STANDARD_CACHE COLD

```text
status=PASS
effective=HTTP_ENGINE
cacheBytes=4,758,593
positionMs=5,812
```

COLD preparation asserted zero retained bytes before playback. After playback the process-owned SimpleCache retained 4,758,593 bytes.

### STANDARD_CACHE WARM with origin stopped

```text
status=PASS
effective=HTTP_ENGINE
cacheBytesAtPreparation=4,758,593
positionMs=2,779
originStoppedBeforeWarm=true
```

The application process was recreated after the COLD phase. The Media Lab origin had already been stopped. Playback advanced beyond 2.7 s using the retained cache prefix.

### Explicit offline error

```text
status=PASS
effective=DEFAULT_HTTP
errorCode=ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
```

The uncached DIRECT path surfaced a structured Media3 network failure instead of crashing or invoking any Sponge recovery behavior.

### Host Media Lab trace

Online DIRECT + COLD generated:

- request rows: 52
- HTTP statuses: 52 × 200
- body bytes written: 18,450,430
- video segment requests: 24
- audio segment requests: 21
- manifest requests: 2
- init-video requests: 2
- init-audio requests: 2
- scenario hash was identical on every trace row.

The trace therefore proves separate A/V DASH fetches. It is not used to infer Android timing.

## Implementation decisions confirmed

- DIRECT has no persistent playback cache.
- STANDARD_CACHE uses `CacheDataSource + SimpleCache + StandaloneDatabaseProvider + LeastRecentlyUsedCacheEvictor`.
- one process-scoped SimpleCache owns `cacheDir/m0-standard-cache`.
- COLD removes retained resources through the Cache API.
- WARM reuses retained coverage across process recreation.
- HttpEngine internal HTTP cache is disabled.
- RECOMMENDED_PLATFORM resolves to HttpEngine on API 36.
- DEFAULT_HTTP remains independently selectable.
- both DIRECT and STANDARD_CACHE use the same explicit `DashMediaSource`/DataSource seam.
- no custom LoadControl/buffer constants were introduced.
- no Sponge prefetch/scheduler/download-ahead behavior exists in the baseline.

## Result

**PASS for M0-C correctness.**

The reference subjects are sufficiently defined for M0-D instrumentation.

## Known limitations

- Emulator timing values above are functional observations only; they are not performance baselines.
- The smoke verifies one API 36 runtime. Older API compatibility belongs to the later CI matrix.
- WARM proves a retained prefix, not a fully offline copy of the 180 s item.
- N1/N4 player measurement is deferred to M0-D/M0-F.
- `adb reverse` is deterministic lab plumbing and provides no VPN/default-route evidence.
- No conclusion about HTTP/3/QUIC performance is made from the HTTP/1.1 Media Lab.
