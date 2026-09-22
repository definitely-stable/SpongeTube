# SpongeTube Verification & Benchmark Plan v0.2

Status: **Provisional**
Date: **2026-09-21**

## 1. Rule: architecture must be falsifiable

A SpongeTube performance or resilience claim is accepted only when:

1. the scenario is reproducible and versioned;
2. the compared baseline/mode is named;
3. the metric definition is explicit;
4. the measurement layer is appropriate for the claim;
5. raw result artifacts are retained;
6. a concise evidence summary is committed under `.work/evidence/`;
7. uncertainty and known limitations are reported;
8. a regression threshold is introduced only after pilot evidence supports it.

Configured impairment is not automatically measured impairment. Emulator correctness is not representative device performance. Live YouTube compatibility is not a deterministic benchmark.

Numeric product targets start as Provisional and become release gates only after repeatable evidence exists on representative physical devices.

## 2. Laboratory architecture: one responsibility per layer

SpongeTube intentionally does not build one universal "network emulator".

```text
L0  Deterministic model tests
    fake clocks / fake data / property and state-machine tests

L1  Sponge Media Lab origin
    HTTP semantics / fixtures / Range / deterministic delivery N0/N1/N4

L2  Transport-fault layer (M2)
    connection timeout / reset / truncation / slow close
    candidate: Toxiproxy or a smaller evidence-backed equivalent

L3  Packet/network layer (M2)
    RTT / jitter / loss / burst loss / reorder / duplicate / corruption
    candidate: scoped Linux tc/netem

L4  Android playback harness
    DIRECT / STANDARD_CACHE / later SPONGE

L5  Device performance layer
    Macrobenchmark / Perfetto / physical-device matrix

L6  Real-provider compatibility
    bounded YouTube probes; no deterministic performance conclusions
```

A failure should be attributable to the lowest layer that can reproduce it.

M0 implements L0/L1/L4/L5 foundations. M2 introduces L2/L3 and Android route/VPN semantics.

## 3. Deterministic Media Lab boundary

M0 Media Lab provides deterministic **application-layer media delivery**:

- fixed fixture bytes;
- deterministic HTTP Range behavior;
- deterministic first-body delay;
- session-global aggregate body pacing;
- session-global no-progress windows;
- structured request/session traces.

It does not claim to emulate:

- RTT;
- packet loss/reorder/corruption;
- TCP congestion control;
- QUIC/HTTP/3 behavior;
- VPN route switching;
- Android default-network replacement.

The Media Lab uses physically independent data and control listeners/executors. A media blackout must not prevent host orchestration/health/config requests from being scheduled.

Android uses `adb reverse` only for deterministic media-delivery tests. `adb reverse` changes the path and therefore **must not** be used as evidence for VPN/default-route behavior. N7 and equivalent route-policy tests require a path that actually traverses Android's selected network.

## 4. Fault model

Each scenario is composed from independent fault planes instead of growing an unbounded list of monolithic profile IDs.

### 4.1 Delivery plane

Owned by Media Lab.

Examples:

- first-body delay;
- aggregate media-body rate;
- scheduled no-progress window;
- deterministic burst schedule where later required.

### 4.2 Transport plane

Owned by an M2 proxy/fault injector outside Media Lab.

Examples:

- connection timeout;
- reset;
- connection close/truncation;
- slow close;
- connection-level data limit.

A Toxiproxy-style mechanism is a candidate because its control plane is separate from proxied traffic and faults operate at the TCP stream boundary.

### 4.3 Packet/network plane

Owned by an M2 scoped network emulator.

Examples:

- RTT/jitter;
- random or burst loss;
- reorder;
- duplication;
- corruption;
- rate/slot behavior.

If `tc/netem` is selected, stochastic profiles record the explicit random seed. Do not shape the host loopback globally when that would also perturb ADB/control traffic; use a scoped interface/namespace/path or another isolated mechanism.

### 4.4 Provider plane

Owned by deterministic HTTP/provider simulation.

Examples:

- 403 after defined request/time boundary;
- 429 + Retry-After;
- descriptor/signed-URL expiry;
- refresh succeeds/fails;
- bounded provider-request budget.

Provider wall-clock semantics use a separate virtual wall clock where needed. They do not reuse the monotonic clock used for durations/deadlines.

### 4.5 Android route plane

Owned by Android integration tests.

Examples:

- validated network disappears;
- default network changes;
- VPN default route disappears/reappears;
- privacy policy prevents silent direct-network fallback.

These scenarios cannot be proven by host-only proxying or `adb reverse`.

### 4.6 Storage plane

Owned by cache/storage harness.

Examples:

- quota pressure;
- slow writes;
- process death during commit;
- eviction pressure;
- recovery after incomplete temporary state.

## 5. Initial scenario matrix

The IDs remain useful shorthand, but `plane` is normative.

| ID | Plane | Scenario | Milestone |
|---|---|---|---|
| N0 | Delivery | CONTROL: no artificial delay, unlimited rate, no no-progress window | M0 |
| N1 | Delivery | aggregate A/V pacing = 0.50 × committed F1 reference playback bitrate + 120 ms first-body delay | M0 |
| N2 | Network | high latency/jitter; exact values resolved in scenario artifact | M2 |
| N3 | Delivery/Network experiment | burst/blackout pattern; plane selected explicitly per experiment | M2 |
| N4 | Delivery | canonical 120 s session-wide media-body no-progress window | M0 |
| N5 | Network | lossy/burst-loss profile with explicit seed | M2 |
| N6 | Transport/Route | connection reset or default-network replacement; these are separate variants | M2 |
| N7 | Android route | VPN-like default-route disappearance/reappearance | M2 |
| N8 | Provider | deterministic 403 | M2 |
| N9 | Provider | deterministic 429 + Retry-After | M2 |
| N10 | Provider | expired descriptor/fetch mapping; refresh path exercised | M2 |
| N11 | Storage | quota/slow-write/storage-pressure profile | M2 |

The old shorthand "N0 = 50 Mbps / 20 ms RTT" is removed: M0 N0 is a control with no artificial network semantics.

## 6. Scenario identity and reproducibility

A profile label is not enough to identify a benchmark.

Every resolved scenario has a canonical machine-readable representation containing, as applicable:

```text
schemaVersion
scenarioId
plane
firstBodyDelayMs
aggregateRateRatio
resolvedAggregateRateBps
writeQuantumBytes
noProgressStartAfterMs
noProgressDurationMs
transportFaults
networkFaults
providerFaults
storageFaults
randomSeed
```

`scenarioHash = SHA-256(canonical resolved scenario bytes)`.

Fixture identity is independent and includes the committed fixture manifest/payload hashes.

Two runs are directly comparable only when the variables that should remain controlled have matching identities. If a scenario/fixture/transport/device state changes intentionally, the report names it as the experimental variable.

Future random scenarios always persist their seed.

## 7. Benchmark subjects

Compare three playback modes when they exist:

```text
A. DIRECT
   Media3 -> standard transport -> Media Lab

B. STANDARD_CACHE
   Media3 -> CacheDataSource/SimpleCache -> same transport -> Media Lab

C. SPONGE
   Media3 PlaybackBridge -> Sponge Core -> same controlled source
```

For B, cache state is part of identity:

- NONE for Direct;
- COLD after explicit reset;
- WARM with defined retained coverage.

Do not tune Media3 buffer constants to make C look better.

Transport experiments start from:

- recommended platform path (HttpEngine where runtime support exists);
- DefaultHttpDataSource portable fallback.

OkHttp/Cronet Embedded or another backend is added only for a measured M2 question.

## 8. Correctness gates

Correctness is pass/fail and does not require statistical significance.

### Fetch ownership

- the same FetchKey is not fetched twice concurrently;
- playback joining an in-flight prefetch does not restart the request;
- cancellation of one consumer does not cancel remaining consumers.

### Coverage

- PlayableCoverage is the intersection of required track coverage;
- refreshed source URLs do not change stable cache identity;
- different quality representations are not merged as one track;
- seeks use timeline/range mapping rather than hard-coded segment duration.

### Failure recovery

- valid persisted coverage survives 403/expiry/route change;
- process death during a fetch leaves valid committed data or recoverable temporary state;
- network restoration fetches only missing coverage.

### VPN/default route

- the active VPN is respected as Android's system-default route;
- unexpected VPN loss does not silently trigger direct-network media fetch under the default privacy policy;
- recovery state is deterministic and observable.

### Media Lab correctness

- Range contract is RFC-correct for the supported subset;
- N1 bandwidth is session-global across simultaneous A/V;
- N4 is session-global across in-flight and newly opened media requests;
- data-plane blockage cannot starve the independent control listener;
- fixture hashes and structural/conformance evidence match the committed fixture set.

## 9. Metric groups

### 9.1 Lab accuracy

Before accepting player comparisons, measure the harness itself:

- observedRateBps;
- rateErrorPct;
- observedFirstBodyDelayMs;
- firstBodyDelayErrorMs;
- observedNoProgressDurationMs;
- noProgressDurationErrorMs;
- maxSchedulerSlipMs;
- host-path post-gate socket-drain characterization.

No fixed error tolerance is invented before pilot runs characterize runner/host jitter.

### 9.2 Playback

- TTFF;
- stall count;
- total stall duration;
- rebuffer ratio;
- seek-to-frame latency;
- recovery-to-media latency;
- recovery-to-playback latency.

Custom SpongeTube event definitions remain normative. Media3 PlaybackStats is recorded as a cross-check where its semantics map cleanly; disagreement is an instrumentation signal, not something to average away.

### 9.3 Resilience

- playable reserve seconds;
- reserve at outage start;
- outage duration;
- survival margin = reserveAtOutageStart - outageDuration;
- outage survived without stall;
- route-switch continuity;
- descriptor-refresh recovery success.

### 9.4 Network efficiency

- network media bytes;
- unique media coverage bytes;
- duplicate range bytes;
- request count;
- retry amplification;
- fetch amplification = networkMediaBytes / uniqueCoverageBytes;
- prefetch usefulness;
- wasted-prefetch ratio.

Wasted prefetch is time-bounded: bytes fetched speculatively but neither consumed nor explicitly retained before the benchmark's defined terminal/eviction boundary.

### 9.5 Storage

- unique media bytes persisted;
- write amplification;
- file count;
- DB/index operations per media minute;
- fsync cost where measurable;
- recovery time after process death;
- eviction latency.

### 9.6 Device impact

- CPU time;
- RSS/PSS/high-water memory where available;
- GC time/pressure;
- disk I/O;
- battery/energy delta where measurement quality permits;
- thermal status transitions;
- dropped frames/audio underruns where relevant.

## 10. Clock domains and correlation

Host Media Lab monotonic time and Android monotonic time are separate domains.

Never subtract them directly.

Cross-domain joining uses stable identifiers:

- sessionId;
- requestId;
- scenarioHash;
- fixture/resource/range identity.

Media Lab response headers expose lab-only correlation IDs. B2 adds session transition events.

A future explicit clock-synchronization experiment may estimate offsets for diagnostics, but benchmark correctness must not depend on an assumed synchronized clock.

## 11. Run manifest and artifact contract

Every automated benchmark run produces a versioned `run-manifest.json` before results are interpreted.

Minimum identity:

```json
{
  "schemaVersion": 1,
  "runId": "...",
  "gitCommit": "...",
  "fixture": {
    "id": "F1",
    "manifestSha256": "..."
  },
  "scenario": {
    "id": "N4",
    "sha256": "..."
  },
  "playback": {
    "mode": "DIRECT",
    "transport": "RECOMMENDED_PLATFORM",
    "cacheState": "NONE"
  },
  "device": {},
  "runtime": {},
  "orderSeed": 12345
}
```

Recommended artifact layout:

```text
run/
  manifest.json
  result.json

  server/
    scenario.json
    requests.jsonl
    events.jsonl
    summary.json

  android/
    playback-events.jsonl
    logcat.txt

  perfetto/
    trace.pftrace
    summary.pb

  calibration/
    result.json
```

Large traces stay in CI/artifact storage. Only concise evidence summaries and small canonical fixtures belong in Git.

## 12. Benchmark protocol

### 12.1 Separate correctness from performance

Correctness runs answer yes/no invariant questions.

Performance runs estimate distributions and differences. One successful run is not a performance result.

### 12.2 Experimental order

Avoid running all A samples, then all B samples.

Use interleaved/block-randomized ordering with a persisted seed, for example balanced ABC/BCA/CAB blocks or an equivalent design appropriate to the number of modes.

Cache-state runs remain explicit; COLD and WARM are not mixed in one statistical population.

### 12.3 Warmup and compilation

The benchmark identity records:

- build variant;
- debuggable/profileable state;
- minification where relevant;
- Android Macrobenchmark CompilationMode;
- Baseline Profile presence/state;
- startup mode where applicable.

A/B/C comparisons use the same compilation state unless compilation is itself the experimental variable.

### 12.4 Repetitions and statistics

Pilot runs determine required repetitions and expected variance.

For small samples report:

- every raw observation;
- median;
- min/max;
- IQR where meaningful.

Do not present p95 as meaningful for a tiny sample.

For mature repeated comparisons prefer paired deltas/effect size with an uncertainty interval. A regression gate is introduced only after baseline variance is understood.

Do not silently discard outliers. Mark a run invalid only for a predeclared reason and retain its raw artifact.

### 12.5 Thermal/order contamination

Record thermal state before and after physical-device performance blocks.

If a device enters a materially different thermal regime, mark the run validity explicitly (for example `THERMALLY_CONTAMINATED`) rather than deleting it from history.

## 13. Android Macrobenchmark and Perfetto

Macrobenchmark controls app start/process/compilation state and records repeatable device traces. It is used for production-like Android performance, not as a replacement for media-specific events.

Perfetto raw traces are retained for root-cause analysis.

M0-D introduces a versioned Perfetto TraceSummary spec for stable machine-readable extraction where supported, for example:

- process CPU time;
- memory summary;
- I/O;
- GC time;
- selected custom trace-section durations.

TraceSummary-derived fields are versioned and stored in `result.json`/Perfetto summary artifacts. A metric is not made a hard gate until its collection is stable across representative devices/runners.

## 14. Fixture validation

Committed fixtures are validated at multiple levels:

1. SHA-256 byte identity;
2. manifest/size/bitrate consistency;
3. container/stream structural inspection;
4. DASH/CMAF conformance for F1 when its media or MPD changes.

Normal PR CI does not rerun heavyweight DASH conformance when bytes are unchanged.

F1 remains exactly one selected video + one selected audio representation to remove adaptation variance from M0.

Additional diagnostic fixture variants are added only when they isolate a real ambiguity; breadth alone is not a reason to grow the binary corpus.

## 15. Physical-device evidence

Emulators are valid for deterministic correctness/API compatibility. They are not representative performance evidence.

A performance conclusion becomes Validated only after a documented physical-device run.

Record at minimum:

- device model;
- SoC where available;
- API/build fingerprint;
- codec capability relevant to the fixture;
- battery percentage;
- charging state;
- thermal state before/after;
- free storage/storage state;
- build/compilation state;
- fixture/scenario hashes;
- run-order seed.

At least one low/mid device is required before release-oriented conclusions; a fast flagship alone can hide scheduling/I/O/memory problems.

## 16. Real YouTube compatibility suite

Run separately from deterministic benchmarks.

Track:

- resolve success;
- VOD playback start success;
- available representations;
- separate A/V behavior;
- 403/provider rejection;
- descriptor refresh;
- provider error distribution;
- extractor/client profile/version.

Use bounded request budgets/backoff. Do not hammer the provider.

A provider compatibility result does not establish media-engine performance.

## 17. CI tiers

```text
PR
  build/lint
  unit/property/state-machine tests
  Media Lab deterministic correctness
  fixture hash/manifest validation
  selected microbench checks only after stable baselines exist

Nightly
  Android API compatibility matrix
  N0/N1/N4 deterministic playback
  Macrobenchmark/Perfetto capture
  broader reliability repetitions

M2 network nightly
  scoped transport/network fault matrix
  seeded stochastic profiles
  route tests on an appropriate Android network path

Release/evidence
  physical-device blocks
  battery/thermal observations
  long no-progress/process-kill/reboot recovery
```

A flaky emulator/network facility is not promoted to a required gate until representative runs show the infrastructure itself is stable.

## 18. Provisional success hypotheses

These remain hypotheses until evidence exists:

- duplicate-fetch ratio converges close to zero;
- an outage shorter than playable reserve produces zero playback stalls;
- Sponge reduces stall time in N3/N4 versus Direct without unacceptable waste;
- route changes do not discard persisted media coverage;
- Smart mode materially reduces wasted prefetch versus unconditional full download;
- a more complex storage backend is adopted only if it produces a measured benefit over the simpler backend.

## 19. Evidence format

Each accepted architecture/performance decision gets:

`.work/evidence/YYYY-MM-DD-<topic>.md`

Required fields:

```text
Question
Hypothesis
Build/commit
Run manifest/artifact reference
Device/API
Fixture identity
Scenario identity
Playback baseline/mode
Procedure
Raw observations
Summary/statistical treatment
Result
Decision
Known limitations
```

Claims such as "Cronet is faster", "packed storage is better" or "20-minute reserve is optimal" are never canonical without scenario-specific evidence.

## 20. Tooling boundary

Current/future candidates:

- JUnit/property/state-machine tests;
- Media3 test utilities/FakeClock principles;
- deterministic Sponge Media Lab;
- AndroidX Benchmark/Macrobenchmark;
- Perfetto + Trace Summarization;
- physical Android devices;
- DASH-IF Conformance for fixture-change validation;
- Toxiproxy or an equivalent transport-fault layer in M2;
- scoped Linux `tc/netem` or an equivalent packet/network layer in M2.

Tool selection is evidence-driven. Do not add a dependency merely because it is popular.

## 21. Reference basis

- Media3 FakeClock: https://developer.android.com/reference/androidx/media3/test/utils/FakeClock
- Media3 analytics/PlaybackStats: https://developer.android.com/reference/androidx/media3/exoplayer/analytics/package-summary
- Android Macrobenchmark: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- Perfetto Trace Summarization: https://perfetto.dev/docs/analysis/trace-summary
- GStreamer Validate scenarios: https://gstreamer.freedesktop.org/documentation/gst-devtools/gst-validate-scenarios.html
- DASH-IF Conformance: https://github.com/Dash-Industry-Forum/DASH-IF-Conformance
- Toxiproxy: https://github.com/Shopify/toxiproxy
- Linux tc-netem: https://man7.org/linux/man-pages/man8/tc-netem.8.html


## 22. M1 pre-implementation acceptance contract

M1 acceptance validates correctness and resilience, not representative device performance.

### 22.1 Measurement domains

The following values must never be conflated:

\`\`\`text
network bytes
  -> receiving bytes
  -> sealed bytes
  -> verified bytes
  -> durable bytes
  -> published per-track coverage
  -> playable coverage
  -> durable playable reserve from playhead

Media3 player buffered-ahead is recorded independently.
\`\`\`

A runtime metric cannot be its own acceptance oracle. Canonical evidence must include an independent reconstruction of published coverage from immutable extent files plus committed metadata.

### 22.2 Fixed semantic coverage seeds

M1 comparisons use deterministic semantic seeds, never "whatever the previous cold run happened to retain".

Canonical positive targets for F1:

- \`S0\`: no published playable media coverage;
- \`S10\`: at least 10 s contiguous playable coverage from media origin;
- \`S30\`: at least 30 s;
- \`S60\`: at least 60 s;
- \`S120\`: at least 120 s.

A seed builder selects complete required-track units necessary to reach the target. The committed seed manifest records exact extents, per-track intervals, representation identities, initialization dependencies, actual playable intervals and manifest hash.

Boundary/negative seeds are also required:

- video hole;
- audio hole;
- missing required initialization/index dependency;
- partial/truncated tail;
- wrong representation identity.

The verifier must prove that these invalid/incomplete resources do not overstate PlayableCoverage.

### 22.3 N4 recovery variants

M1 extends the M0 N4 delivery fault into explicit recovery scenarios.

\`N4R-SHORT\`
- outage duration is shorter than initial durable playable reserve;
- playback must not incur a network-dependent rebuffer attributable to reserve exhaustion;
- no second independent remote owner fetch may occur.

\`N4R-EXHAUST\`
- outage exceeds initial durable playable reserve;
- a stall is allowed after usable local data and Media3's already-buffered data are exhausted;
- coverage must never be inflated to hide the stall;
- retry activity remains bounded by the M1 request budget contract.

\`N4R-RESTORE\`
- outage exceeds reserve and produces a stall;
- transport resumes while the playback session remains alive;
- missing coverage is fetched through FetchBroker, durably published, and playback resumes without recreating the whole player/session solely as a recovery mechanism.

\`N4R-FLAP\` is SHOULD for M1:
- repeated no-progress/restore cycles;
- no overlapping owner fetch storm;
- no coverage corruption;
- deterministic final state.

Evidence separates:
- configured outage start/end;
- origin gate actually blocking/unblocking;
- client-observed no-progress interval;
- durable reserve timeline;
- player stall interval;
- first successful post-restore fetch progress;
- first new durable publication;
- playback recovery.

### 22.4 Canonical M1 MUST gates

| ID | Setup/stimulus | Required evidence | Pass condition |
| --- | --- | --- | --- |
| M1-ACC-01 | write one extent | extent state log + independent file/hash check | coverage appears only after PUBLISHED |
| M1-ACC-02 | crash at each extent boundary | restart recovery report | no crash point produces phantom coverage |
| M1-ACC-03 | corrupt/missing published file | recovery scan + coverage cross-check | invalid row contributes zero playable coverage |
| M1-ACC-04 | fixed S0/S10/S30/S60/S120 | seed manifest + independent verifier | runtime and reconstructed coverage agree exactly on interval semantics |
| M1-ACC-05 | holed/partial/wrong-representation seeds | interval reconstruction | reserve ends at first required-track hole; invalid extent contributes zero |
| M1-ACC-06 | two consumers request same FetchKey concurrently | fetch ownership log + origin trace | one physical origin owner fetch; both consumers join |
| M1-ACC-07 | cancel one joined consumer | ownership/cancellation log | remaining consumer is not cancelled |
| M1-ACC-08 | playback joins reserve in-flight fetch | origin trace + broker events | no cancel/restart duplicate fetch |
| M1-ACC-09 | seek fully inside published coverage | Media3 + store trace | no remote media request is required |
| M1-ACC-10 | seek into missing coverage | bridge/broker trace | remote request goes only through FetchBroker |
| M1-ACC-11 | process death after published coverage | restart + independent reconstruction | identical valid coverage survives restart |
| M1-ACC-12 | N4R-SHORT | reserve/player/network timelines | outage shorter than durable reserve does not cause reserve-exhaustion rebuffer |
| M1-ACC-13 | N4R-EXHAUST | same | any stall is consistent with actual reserve exhaustion; no coverage overclaim |
| M1-ACC-14 | N4R-RESTORE | same + recovery events | missing coverage publishes after restore and playback resumes |
| M1-ACC-15 | HTTP partial continuation variants | request/response range evidence | incompatible range/full-body/identity responses are never appended as valid continuation |
| M1-ACC-16 | runtime CoverageIndex snapshot | independent offline reconstruction | interval sets and reserve semantics match exactly |

Deterministic correctness gates normally require one successful canonical execution plus targeted unit/state-machine coverage. No arbitrary performance repetition count is encoded into correctness acceptance.

### 22.5 SHOULD gates

- \`N4R-FLAP\`;
- wider corruption and storage-pressure matrix;
- API 23/API 34 compatibility subset where implementation touches platform-specific behavior;
- property/state-machine generation for interval and lifecycle transitions;
- repeated calibration of noisy timing observations.

### 22.6 LATER gates

Not M1 exit criteria:

- physical-device performance effect sizes;
- p95/p99 latency targets;
- battery/thermal conclusions;
- transport winner;
- Smart Buffer target optimization;
- background Keep Offline scheduling;
- packed-container storage optimization.

### 22.7 M1 artifact set

Canonical M1 runs use versioned machine-readable artifacts:

- \`m1-run-manifest-v1\`;
- \`seed-manifest-v1\`;
- \`coverage-snapshot-v1\`;
- \`extent-events-v1\`;
- \`fetch-events-v1\`;
- \`recovery-summary-v1\`.

The committed evidence summary references raw CI artifacts by run identity/digest and records limitations explicitly.
