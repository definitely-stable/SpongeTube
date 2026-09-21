# M0-D — Measurement & Benchmark Harness

Status: **Implementation plan**
Issue: #7
Depends on: #5, #6

## 1. Purpose

M0-D turns the frozen Media Lab + Media3 baselines into machine-readable evidence.

It does not change:
- baseline transport selection;
- cache semantics;
- Media3 buffering policy;
- F1 fixture bytes;
- N0/N1/N4 delivery semantics.

M0-D observes those subjects.

## 2. Normative evidence flow

```text
Android playback
  -> schema-v1 monotonic events
  -> playback reducer
  -> playback metrics

Media Lab request/session/calibration JSONL
  -> host trace reducer
  -> network + lab-accuracy metrics

run identity + both metric sets
  -> run-manifest.json
  -> result.json

Macrobenchmark / Perfetto
  -> raw trace
  -> versioned trace summary
  -> device/process metrics
```

Android and host timestamps are never subtracted across clock domains.

## 3. Delivery phases

### D1 — Evidence kernel

Implement first because later traces are not trustworthy without fixed semantics.

Android event schema:
- SESSION_STARTED
- PLAY_REQUESTED
- PREPARE_STARTED
- PLAYBACK_READY
- FIRST_FRAME
- BUFFERING_STARTED
- BUFFERING_ENDED
- SEEK_STARTED
- SEEK_COMPLETED
- FIRST_FRAME_AFTER_SEEK
- PLAYBACK_ENDED
- PLAYBACK_ERROR
- SESSION_ENDED

Buffering reason:
- STARTUP
- REBUFFER
- SEEK

Requirements:
- schemaVersion = 1;
- per-session monotonically increasing sequence;
- Android `SystemClock.elapsedRealtimeNanos()` only;
- JSONL output preserves raw events;
- state reducer rejects impossible/ambiguous intervals instead of silently repairing them.

Normative metrics:
- TTFF = FIRST_FRAME - PLAY_REQUESTED;
- stallCount = closed REBUFFER intervals after FIRST_FRAME while playback intends progress and no seek is active;
- stallTotal = sum of those intervals;
- seekToFrame = FIRST_FRAME_AFTER_SEEK - SEEK_STARTED.

Startup/seek buffering never counts as a normal-playback stall.

Host trace reducer:
- REQUEST_COUNT;
- NETWORK_BYTES;
- UNIQUE_RANGE_BYTES;
- DUPLICATE_RANGE_BYTES;
- HTTP_ERROR_COUNT.

For each resource, actual served coverage is the prefix:
`[resolvedRangeStart, resolvedRangeStart + bodyBytesWritten)`.

Duplicate bytes:
`sum(actual served media bytes) - size(union(actual served intervals per resource))`.

Control-plane rows and non-fixture responses do not contaminate media-byte metrics.

### D2 — Production-like benchmark target

Create exactly one new Gradle project:

`:benchmark`

Use:
- AndroidX Benchmark 1.5.0;
- `com.android.test`;
- Macrobenchmark;
- UiAutomator 2.4.0 where interaction is required.

App adds a `benchmark` build type:
- initialized from release;
- non-debuggable;
- locally debug-signed;
- profileable for shell;
- cleartext permitted only for benchmark localhost Media Lab;
- build/minify/profileable/compilation state written into run identity.

Do not generate a Baseline Profile in M0-D.

ProfileInstaller 1.4.1 may be present only because the Macrobenchmark toolchain requires it; its presence/state is recorded and is not treated as an optimization result.

### D3 — Run manifest and correlation

Before interpreting a run, produce schema-v1 identity containing:

```text
runId
gitCommit
fixture id + manifest/payload hash
scenario id + scenarioHash
baseline mode
cacheState
requested/effective transport
buildType
debuggable/profileable/minify
Macrobenchmark compilation mode
startup mode
device API/fingerprint
orderSeed
```

Correlation:
- sessionId;
- lab requestId header where captured;
- scenarioHash;
- fixture/resource/range identity.

No cross-clock subtraction.

Artifact layout:

```text
run/
  manifest.json
  result.json
  server/
    requests.jsonl
    events.jsonl
    calibration.json
    summary.json
  android/
    playback-events.jsonl
    logcat.txt
  perfetto/
    trace.pftrace
    summary.json
```

### D4 — Perfetto / cross-checks

Add AndroidX Tracing 2.0.2 to trace selected measurement phases.

Macrobenchmark owns process/start/compilation state and raw Perfetto capture.

Version TraceSummary extraction independently from playback metrics.

Initial stable device/process fields:
- process CPU time;
- memory summary where supported;
- I/O where supported;
- GC time where supported;
- selected custom trace sections.

Media3 PlaybackStats is retained as a semantic cross-check where definitions map cleanly. It never replaces the custom event reducer.

## 4. Benchmark protocol boundary

Correctness and performance remain separate.

PR/M0-D correctness:
- schema/reducer unit tests;
- trace interval tests;
- benchmark module builds;
- short emulator capture only after stable.

Performance:
- physical device required before a result is called representative;
- repetitions/order/statistics belong to M0-F acceptance evidence;
- no p95 claim from tiny samples.

## 5. Dependency policy

Immediate:
- Benchmark 1.5.0;
- Tracing 2.0.2;
- AndroidX Test runner 1.7.0;
- AndroidX Test ext.junit 1.3.0;
- UiAutomator 2.4.0;
- ProfileInstaller 1.4.1 only as Macrobenchmark toolchain support.

Do not add:
- Espresso unless a concrete UI interaction cannot be expressed otherwise;
- protobuf solely for result files;
- Room/KSP/DI;
- third-party analytics/logging libraries.

JSON evidence remains explicit/versioned and dependency-light.

## 6. CI policy

Normal PR:
- `check assembleDebug`;
- unit tests for reducers/schema;
- benchmark module compile.

Temporary M0-D evidence workflows may capture emulator traces while #7 is open.

Permanent emulator/nightly scheduling belongs to #8.

Large Perfetto traces remain CI artifacts with short retention. Concise accepted evidence goes under `.work/evidence/`.

## 7. Exit criteria

- [ ] schema-v1 Android event model exists;
- [ ] Android timestamps use one monotonic clock;
- [ ] TTFF reducer has tests;
- [ ] REBUFFER-only stall reducer has tests;
- [ ] seek-to-frame reducer has tests;
- [ ] malformed/incomplete event sequences have explicit result semantics;
- [ ] host network summary excludes control rows;
- [ ] duplicate range union has overlap/nesting/disjoint tests;
- [ ] actual written bytes, not planned bytes, define served coverage;
- [ ] `:benchmark` exists;
- [ ] app benchmark variant is non-debuggable/profileable;
- [ ] Benchmark 1.5.0 and Tracing 2.0.2 are pinned;
- [ ] run-manifest schema binds baseline/scenario/fixture/build/device identity;
- [ ] host/device correlation never subtracts separate monotonic clocks;
- [ ] raw Perfetto artifact contract is defined;
- [ ] emulator evidence is labeled correctness-only;
- [ ] no M1/Sponge behavior leaks into M0-D.
