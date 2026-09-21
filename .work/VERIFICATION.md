# SpongeTube Verification & Benchmark Plan v0.1

Status: **Provisional**
Date: **2026-09-21**

## 1. Rule: architecture must be falsifiable

A SpongeTube performance claim is accepted only when:

1. the scenario is reproducible;
2. the baseline is named;
3. the metric definition is explicit;
4. raw result artifacts are retained by CI;
5. a short evidence summary is committed under `.work/evidence/`;
6. regressions have a declared threshold.

Numeric product targets start as provisional and become release gates only after baseline runs on representative devices.

Emulator results may validate deterministic correctness and API compatibility, but emulator timing/throughput/power numbers are not accepted as representative device-performance evidence. A performance conclusion becomes Validated only after a documented physical-device run.

## 2. Benchmark subjects

Compare three modes where possible:

```text
A. Direct Media3 playback
B. Media3 bounded cache baseline
C. Sponge Smart Buffer
```

For storage experiments, compare:

```text
SimpleCache / loose extents
vs
packed extent prototype
```

For transport experiments, start from:

```text
Media3 recommended platform path (HttpEngine where supported)
vs
Media3 portable DefaultHttpDataSource fallback
```

Additional candidates such as OkHttp or Cronet are introduced only when a measured M2 question justifies them.

Never claim transport superiority from synthetic request throughput alone; include playback continuity, compatibility and device/resource impact.

## 3. Deterministic test origin

Performance benchmarks must not depend on live YouTube behavior.

Provide a controlled VOD origin containing:

- fixed AVC/VP9/AV1 fixtures where practical;
- separate audio/video tracks;
- deterministic DASH/HLS/progressive fixtures;
- byte-range support;
- forced 403 after configurable request count/time;
- descriptor-expiry simulation;
- delayed response and connection-reset injection.

A network impairment layer should provide repeatable bandwidth, latency, jitter, loss and blackouts. Toxiproxy or Linux `tc/netem` are candidates; selection is evidence-driven.

Real YouTube tests are **compatibility probes**, not deterministic performance benchmarks.

## 4. Network profile matrix

Initial reproducible profiles:

| ID | Profile | Shape |
|---|---|---|
| N0 | Good Wi-Fi | 50 Mbps, 20 ms RTT, no loss |
| N1 | Slow | 1.5 Mbps, 120 ms RTT |
| N2 | High latency | 8 Mbps, 450 ms RTT, jitter |
| N3 | Burst/blackout | 8 Mbps for 15 s, 0 for 30 s, repeat |
| N4 | Long outage | healthy network → 120 s blackout |
| N5 | Lossy | 5 Mbps, 150 ms RTT, 2% loss |
| N6 | Route reset | active connection reset/default-network replacement |
| N7 | VPN flap | VPN-like default route disappears/reappears in test harness |
| N8 | Provider reject | 403 after configured point |
| N9 | Rate limited | 429 + Retry-After |
| N10 | Expired descriptor | old fetch mapping rejected; refresh succeeds |
| N11 | Storage pressure | limited quota / artificial slow writes |

Numbers are initial benchmark fixtures, not claims about real networks.

## 5. Correctness gates

These are hard invariants and do not wait for performance tuning.

### Fetch ownership

- same FetchKey must not be fetched twice concurrently;
- player joining an in-flight prefetch must not restart the request;
- cancellation of one consumer must not cancel remaining consumers.

### Coverage correctness

- PlayableCoverage is the intersection of required track coverage;
- cache identity is stable across source URL refresh;
- quality representations are never mixed as if they were one track;
- seek uses timeline/range mapping, not hardcoded segment duration.

### Failure recovery

- valid persisted coverage remains after 403, URL expiry or route change;
- process kill during fetch leaves either a valid committed extent or recoverable temporary state;
- storage/database crash ordering cannot delete the only valid media copy;
- network restoration resumes missing coverage without redownloading committed extents.

### VPN behavior

- active VPN is respected as the system default route;
- unexpected VPN disappearance cannot silently trigger a direct-network fetch under the default privacy policy;
- UI/recovery state is deterministic.

## 6. Primary metrics

### Playback

- time to first frame (TTFF), p50/p95;
- stall count per playback hour;
- total stall duration per playback hour;
- rebuffer ratio;
- seek-to-frame latency;
- recovery latency after route restoration.

### Resilience

- playable reserve seconds;
- probability of surviving a defined outage without stall;
- reserve growth rate;
- descriptor-refresh recovery success;
- route-switch continuity rate.

### Network efficiency

- unique media bytes fetched;
- duplicate-fetch bytes / unique bytes;
- request count;
- 403/429 retry amplification;
- wasted-prefetch ratio.

Wasted prefetch is data fetched but never consumed or pinned before eviction. The definition must be time-bounded in each benchmark report.

### Storage

- unique media bytes persisted;
- bytes written to storage / unique media bytes (write amplification);
- file count;
- DB operations per media minute;
- fsync cost;
- cache recovery time after process death;
- eviction latency.

### Device impact

- CPU time;
- memory high-water mark;
- GC pressure;
- battery/energy delta vs baseline where measurable;
- thermal status transitions;
- disk I/O saturation.

## 7. Provisional success hypotheses

These are hypotheses for M1/M2, not release promises:

- duplicate-fetch ratio should converge to approximately zero;
- if PlayableReserve is greater than an injected outage duration, the outage should cause zero playback stalls;
- Sponge mode should materially reduce stall time in N3/N4 compared with direct Media3;
- route change should not discard persisted coverage;
- Smart mode should use materially less waste than unconditional full-prefetch;
- packed storage is adopted only if it shows measurable benefit over the simpler backend.

## 8. Benchmark layers

### Unit/property tests

Run on every PR:

- Coverage interval algebra;
- FetchKey equality;
- single-flight state machine;
- deadline scheduling;
- reserve policy;
- retry budget;
- retention/eviction;
- crash-recovery transitions.

### JVM/microbenchmarks

Run on PR or nightly depending on cost:

- CoverageIndex operations;
- scheduler queue operations;
- DB/index operations;
- packed/loose extent lookup;
- policy calculations.

### Android instrumentation

Run on representative emulator/API matrix:

- Media3 integration;
- DataSource/PlaybackBridge;
- process recreation;
- storage behavior;
- foreground/background transitions.

### Macrobenchmark/Perfetto

Nightly/release:

- startup;
- video-open-to-first-frame;
- seek;
- scroll/feed responsiveness when added;
- CPU/memory/frame timing;
- I/O traces.

### Physical device suite

Required before release candidate:

At minimum include:

- low/mid Android device;
- modern mid/high device with hardware AV1;
- one device with aggressive OEM background management if available.

The exact device list is evidence work, not frozen here.

## 9. Real YouTube compatibility suite

Run separately from deterministic benchmarks.

Track:

- resolve success rate;
- VOD playback start success;
- available representations;
- separate A/V behavior;
- 403 recovery;
- descriptor refresh;
- provider error distribution;
- extractor version/client profile.

The suite must not hammer the provider. Request budgets and backoff are part of the test.

A compatibility regression is an adapter issue unless Sponge Core invariants also fail.

## 10. M0 baseline contract

Before Sponge Core exists, M0 establishes two non-Sponge reference paths:

```text
A. Direct Media3 playback
B. Media3 CacheDataSource + SimpleCache
```

Both use the same deterministic fixtures and network profiles. Do not tune Media3 buffering to make the later Sponge comparison easier.

M0 must produce a versioned machine-readable result containing build, device/runtime, fixture, network profile, baseline mode, transport, metrics and errors. The exact schema is defined in `.work/milestones/M0.md`.

M0 implements N0, N1 and canonical N4 first. The broader N2/N3/N5–N11 matrix remains specified here for M2 unless an earlier implementation is required to prove the harness.

## 11. CI tiers

```text
PR
  compile/lint
  unit/property tests
  deterministic engine tests
  selected microbench regression checks

Nightly
  Android emulator matrix
  impairment profiles N0-N11
  Macrobenchmark/Perfetto
  real-provider compatibility smoke

Release candidate
  physical-device matrix
  battery/thermal runs
  long-duration interruption tests
  process-kill/reboot recovery
```

## 12. Evidence format

Each accepted architecture/performance decision gets a short file:

```text
.work/evidence/YYYY-MM-DD-<topic>.md
```

Required fields:

```text
Question
Hypothesis
Build/commit
Device/API
Fixture
Network profile
Baseline
Result
Raw artifact link/hash
Decision
Known limitations
```

No statement such as "Cronet is faster", "packed storage is better" or "20-minute reserve is optimal" is canonical without such evidence.

## 13. Tooling baseline

Candidates:

- AndroidX Benchmark 1.5.x;
- Macrobenchmark;
- Perfetto;
- JUnit/property-based tests;
- Android emulator;
- physical device runs;
- deterministic local media origin;
- Toxiproxy and/or `tc/netem`;
- CI artifacts with machine-readable JSON/CSV summaries.

Benchmark tooling itself is version-pinned in code when implementation begins.
