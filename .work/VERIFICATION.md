# SpongeTube Verification & Benchmark Plan v0.2

Status: **Normative — evidence and acceptance policy**
Date: **2026-09-23**

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

Plane ownership is normative. One injected fault has exactly one primary owning plane; a combined scenario lists each constituent fault separately under its own plane. The M2 contract (`.work/milestones/M2.md` section 6) is authoritative for plane names (`DELIVERY`, `TRANSPORT`, `NETWORK`, `PROVIDER`, `ROUTE`, `STORAGE`) and owners.

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

Owned by the ExtentStore fault path / storage harness.

Examples:

- ENOSPC / quota pressure;
- metadata or file I/O failure;
- slow writes;
- process death during commit;
- recovery after incomplete temporary state.

M2 owns only the interaction of storage faults with resilience decisions (N11): storage failures are never classified as provider/network failures, never trigger delivery-binding refresh, and never invalidate valid coverage. Production quota, eviction, retention and user-facing storage-pressure policy belong to M6.

## 5. Initial scenario matrix

The IDs remain useful shorthand, but `plane` is normative. From M2 on, every resolved scenario carries `scenarioFamily`, `variant` and `primaryPlane`; `primaryPlane + variant + resolved parameters + seed` is the experiment identity, and the family ID alone never identifies an experiment.

| ID | Plane | Scenario | Milestone |
|---|---|---|---|
| N0 | Delivery | CONTROL: no artificial delay, unlimited rate, no no-progress window | M0 |
| N1 | Delivery | aggregate A/V pacing = 0.50 × committed F1 reference playback bitrate + 120 ms first-body delay | M0 |
| N2 | Network | high latency/jitter; exact values resolved in scenario artifact | M2 |
| N3 | Delivery **or** Network (per variant) | burst/blackout pattern; e.g. `BURST_DELIVERY_BLACKOUT` (DELIVERY) vs `BURST_PACKET_LOSS` (NETWORK) | M2 |
| N4 | Delivery | canonical 120 s session-wide media-body no-progress window | M0 |
| N5 | Network | lossy/burst-loss profile with explicit seed | M2 |
| N6 | Transport **or** Route (per variant) | `TRANSPORT_RESET` (TRANSPORT) vs `DEFAULT_ROUTE_REPLACEMENT` (ROUTE); different experiments sharing a family label | M2 |
| N7 | Android route | VPN-like default-route disappearance/reappearance | M2 |
| N8 | Provider | deterministic 403 | M2 |
| N9 | Provider | deterministic 429 + Retry-After | M2 |
| N10 | Provider | expired descriptor/fetch mapping; refresh path exercised | M2 |
| N11 | Storage | storage fault interacting with network recovery (ENOSPC/I/O); production quota/eviction policy is M6 | M2 (interaction) / M6 (policy) |

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

M2 resolved scenarios use `m2-scenario-v1`: per-plane fault lists, `randomSeed` (required non-null when any fault is stochastic) and `requiresActualDefaultNetwork` (required true for any ROUTE claim). Canonical bytes and `scenarioHash` are computed by `scripts/measurement/m2_contracts.py` (sorted keys, compact UTF-8 JSON, per-plane faults ordered by `faultId`, integer-only numbers). M0/M1 scenario identities remain valid for their historical evidence.

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

A runtime metric cannot be its own acceptance oracle. Canonical evidence must include an independent reconstruction of published coverage from a committed SQLite metadata snapshot plus independently verified immutable extent files. Lifecycle `PUBLISHED` events are diagnostic only and cannot authorize coverage.

For M1, "independent" means the verifier obtains file facts from the storage root itself. It MUST stat and hash immutable extent files independently of runtime CoverageIndex and independently of metadata values supplied by the app. A caller-supplied map that merely repeats database length/digest fields is not an independent file verifier.

The canonical oracle pipeline is:

```text
committed SQLite snapshot
        +
immutable storage root
        -> independent filesystem verifier
        -> verified-extent-files-v1
        -> independent coverage reconstruction
        -> oracle coverage snapshot
        -> exact comparator
        <-> runtime CoverageIndex snapshot
```

The host oracle must be directly executable against a real run directory/inputs. Runtime CoverageIndex helpers must not be imported as the implementation of the independent interval reconstruction.

### 22.2 Fixed semantic coverage seeds

M1 comparisons use deterministic semantic seeds, never "whatever the previous cold run happened to retain".

Canonical positive targets for F1:

- \`S0\`: no published playable media coverage;
- \`S10\`: at least 10 s contiguous playable coverage from media origin;
- \`S30\`: at least 30 s;
- \`S60\`: at least 60 s;
- \`S120\`: at least 120 s.

A seed builder selects complete required-track units necessary to reach the target. The asset-scoped `seed-manifest-v2` is construction evidence only: it records the exact fixture timeline identity, MediaAssetId, requirement set, selected immutable resources, lengths, SHA-256 values, dependencies, target and rejected attempts. It MUST NOT contain computed per-track/playable coverage or reserve values. Runtime CoverageIndex and the independent host oracle derive those results separately.

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
- a stall is allowed only after all remaining durable playable reserve has already entered Media3's buffered horizon; because Media3 may report `STATE_BUFFERING` with a small non-zero `bufferedPosition - currentPosition`, canonical verification requires `max(0, DurablePlayableReserve - PlayerBufferedAhead) <= 1 ms` rather than the invalid `DurablePlayableReserve == 0` shortcut;
- the 1 ms allowance is solely the quantization of Media3 millisecond position APIs used by the harness, not a playback-performance tolerance;
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
- configured gate commands and origin gate generations;
- origin request actually blocking/unblocking;
- client fetch progress;
- durable reserve timeline;
- player stall interval;
- first successful post-restore fetch progress;
- first new durable publication;
- playback recovery.

Origin and Android timestamps are **not** one clock. Media Lab monotonic time
may order origin-side events and Android elapsed-realtime may order
client/player events, but acceptance never subtracts or directly orders values
across those domains. Cross-domain causal joins use command/request/fetch
correlation identities.

### 22.4 Canonical M1 MUST gates

A MUST gate is not considered executable merely because its prose and JSON Schema exist. Before a slice may claim a gate, the repository must contain:

1. the evidence producer(s);
2. an independent verifier or comparator where required;
3. a deterministic pass/fail execution path wired into the owning slice's verification;
4. at least one negative test proving that a materially wrong result fails.

Missing evidence infrastructure is work for the owning slice, not deferred implementation work for M1-G.


| ID | Setup/stimulus | Required evidence | Pass condition |
| --- | --- | --- | --- |
| M1-ACC-01 | write one extent | extent state log + committed metadata snapshot + independent file/hash check | coverage appears only after committed PUBLISHED metadata and matching immutable file verification |
| M1-ACC-02 | crash at each extent boundary | restart recovery report + post-recovery committed metadata/file snapshots | no crash point produces phantom coverage; a PUBLISHED event without a committed row contributes zero |
| M1-ACC-03 | corrupt/missing published file | recovery scan + coverage cross-check | invalid row contributes zero playable coverage |
| M1-ACC-04 | fixed S0/S10/S30/S60/S120 | seed manifest + independent verifier | runtime and reconstructed coverage agree exactly on interval semantics |
| M1-ACC-05 | holed/partial/wrong-representation seeds | interval reconstruction | reserve ends at first required-track hole; invalid extent contributes zero |
| M1-ACC-06 | two consumers request same FetchKey concurrently | fetch ownership log + attempt-correlated origin trace | one SharedFetch/fetchId owns the key; both consumers join and no physical attempts overlap |
| M1-ACC-07 | cancel one joined consumer | ownership/cancellation log | remaining consumer is not cancelled; final-consumer cancellation keeps CANCELLING ownership until terminal; late demand does not reset request budget |
| M1-ACC-08 | playback joins reserve in-flight fetch | attempt-correlated origin trace + broker events | same fetchId is retained, priority raises RESERVE -> PLAYBACK and no cancel/restart duplicate attempt occurs |
| M1-ACC-09 | seek fully inside published coverage | Media3 + store trace | no remote media request is required |
| M1-ACC-10 | seek into missing coverage | bridge/broker trace | inside the seek marker window a bridge MISS owns a broker attempt, reaches SUCCESS, becomes LOCAL_SERVE, and every origin request goes only through FetchBroker |
| M1-ACC-11 | actual target process death after published coverage | PID-before/PID-after + restart + independent reconstruction | PID changes and identical valid coverage survives restart exactly |
| M1-ACC-12 | N4R-SHORT | reserve/player/network timelines | outage shorter than durable reserve does not cause reserve-exhaustion rebuffer |
| M1-ACC-13 | N4R-EXHAUST | same + composite retry evidence | any stall is consistent with actual reserve exhaustion; no coverage overclaim; per-owner and Media3 retry budgets remain bounded |
| M1-ACC-14 | N4R-RESTORE | same + recovery events + player identity | restore is commanded after an observed stall; post-restore progress publishes through FetchBroker/ExtentStore and the same player resumes |
| M1-ACC-15 | HTTP partial continuation variants | request/response range evidence | incompatible range/full-body/identity responses are never appended as valid continuation |
| M1-ACC-16 | runtime CoverageIndex snapshot | committed metadata snapshot + independently verified files + offline reconstruction | interval sets and reserve semantics match exactly |

Deterministic correctness gates normally require one successful canonical execution plus targeted unit/state-machine coverage. No arbitrary performance repetition count is encoded into correctness acceptance.

For M1-C, API 36 Android instrumentation must publish each canonical seed through the real ExtentStore, close the store, export the runtime \`coverage-snapshot-v2\` plus a consistent copied Room/storage-root bundle, and let the host #48 kernel independently export committed metadata, stat/SHA-256 the immutable files, reconstruct coverage and compare runtime semantics exactly. The seed construction verifier separately checks that the committed extent set matches \`seed-manifest-v2\`; it does not compute coverage. API 23/API 34 run the same storage/engine instrumentation as compatibility checks, while the canonical host-oracle evidence chain runs once on API 36.

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

Canonical M1 evidence is versioned rather than silently rewriting an old contract. #48 v1 artifacts remain valid for historical pre-asset-scoped evidence. M1-C introduces v2 only where MediaAsset identity changes semantics:

- `m1-run-manifest-v1`;
- `m1-acceptance-index-v1` for the M1-G 16/16 gate/proof/digest index;
- `seed-manifest-v2` for M1-C construction evidence;
- `coverage-snapshot-v2` for runtime/oracle asset-scoped coverage;
- `extent-events-v1`;
- `committed-extents-v2` for Room schema v2;
- `verified-extent-files-v1` because independent file facts are unchanged by asset identity;
- `fetch-events-v2` for accepted M1-D/M1-E ownership/attempt evidence; historical v1 remains valid;
- `fetch-events-v3` for M1-F progress-range evidence and failed-attempt origin correlation; v2 remains valid for historical M1-D/M1-E evidence;
- `bridge-events-v1` for M1-E PlaybackBridge local-serve/miss/running-join/cancellation-barrier-wait evidence;
- `recovery-timeline-v1` for Android-domain player/reserve/recovery events;
- `origin-gate-events-v1` for Media Lab-domain manual N4R gate generations and blocked request identities;
- `range-continuation-v1` for M1-ACC-15 request/response Range observations and emitted-byte outcome evidence;
- `recovery-summary-v2` derived by the independent host verifier. Historical `recovery-summary-v1` remains a pre-M1-F contract example and is not canonical M1-F evidence.

Artifact ownership is incremental:

| Artifact | Producer | Independent check | Required by |
| --- | --- | --- | --- |
| `committed-extents-v2` | storage metadata snapshot exporter | schema + oracle ingestion + MediaAsset scope | M1-C |
| `verified-extent-files-v1` | host filesystem verifier | stat + SHA-256 from storage root | M1-C |
| `seed-manifest-v2` | deterministic seed construction producer | fixture manifest + MPD identity; never a coverage oracle | M1-C |
| `coverage-snapshot-v2` | runtime CoverageIndex / independent oracle | exact semantic comparator including MediaAssetId | M1-C |
| `extent-events-v1` | ExtentStore instrumentation | reducer/schema checks | M1-B/M1-F |
| `fetch-events-v2` | FetchBroker | schema validation first, then exact fetchId/attempt/transport-correlation cross-check against origin trace | M1-D/M1-E |
| `fetch-events-v3` | FetchBroker | v2 ownership semantics + chunk-range progress ledger + failed-attempt origin correlation; host recomputes duplicates across owner lifetimes | M1-F |
| `bridge-events-v1` | PlaybackBridge (`PlaybackReadSession`) | schema validation first; every MISS/JOIN/WAIT_EXISTING correlates to a terminal `fetch-events-v2` owner by (sessionId, fetchId); JOIN means an actual RUNNING-owner join, while WAIT_EXISTING means a cancellation-barrier terminal handoff; bijection between origin data-plane requests and broker attempts (no hidden upstream); seek windows checked by event sequence and broker sequence watermark, never by clock comparison | M1-E |
| `recovery-timeline-v1` | benchmark-only Android recovery harness | schema + same-player/state-machine verification | M1-F |
| `origin-gate-events-v1` | Media Lab N4R manual body gate | generation/command/request causal checks | M1-F |
| `range-continuation-v1` | deterministic HttpRangeFetchExecutor harness | strict schema + independent exact request/response/outcome verification; incompatible responses must emit zero accepted bytes | M1-E2 |
| `recovery-summary-v2` | host recovery verifier | strict schema + ownership/range ledger + post-reopen oracle | M1-F |
| `m1-run-manifest-v1` | canonical acceptance harness | schema + bound artifact identities | M1-G |
| `m1-acceptance-index-v1` | canonical acceptance aggregator | schema + exact 16 unique MUST gates + proof membership + SHA-256/size recheck + manifest/commit identity | M1-G |

Every runtime fetch-event row MUST validate against its declared checked-in
schema before semantic/origin verification; retained M1 evidence uses
`fetch-events-v2/v3`, while M2-C runtime evidence uses `fetch-events-v4`
with a typed raw observation. Likewise retained M1 PlaybackBridge evidence
uses `bridge-events-v1`; M2-C runtime evidence uses `bridge-events-v2`
with explicit RecoveryChain identity and nullable real `fetchId`. A
serializer/schema mismatch fails the run. The same rule applies to
`recovery-timeline-v1` and `origin-gate-events-v1`.

For M1-F the host verifier independently builds the full-session accepted-range
ledger across different `fetchId` lifetimes. Per-owner
`duplicateRangeBytes` alone is insufficient because a Media3 retry may create
a new SharedFetch and reset the owner's local accumulator.

For M1-E, M1-ACC-09 passes only when a harness-marked cached-seek window contains zero bridge MISS/JOIN/WAIT_EXISTING rows and zero FetchBroker `ATTEMPT_STARTED` rows between the markers' broker sequence watermarks. M1-ACC-10 passes only when every origin data-plane request of the run correlates to exactly one broker attempt (`transportCorrelationId == requestId`), the attempt count equals the origin data-plane request count, the manifest is served from verified packaged bytes (zero manifest origin requests), and inside the SEEK_TO_MISSING_ISSUED..SEEK_TO_MISSING_SETTLED window at least one bridge MISS is followed by a broker `ATTEMPT_STARTED`, terminal SUCCESS and LOCAL_SERVE for the same read/extent.

Historical `committed-extents-v1`, `seed-manifest-v1`, `coverage-snapshot-v1` and `fetch-events-v1` remain accepted by the evidence/schema suite for already-produced evidence; new M1-C runs use asset-scoped coverage v2 and new M1-D runs use fetch-events-v2.
M1-G executes and publishes the already-working evidence path; it must not become the first place where missing producers or comparators are implemented.

The M1-G run manifest binds the complete canonical seed matrix as `seedId = CANONICAL-MATRIX`; its `manifestSha256` is the SHA-256 of the generated matrix of the ten independently verified `seed-manifest-v2` artifacts. The acceptance index then binds every retained proof file by relative path, SHA-256 and size and fails unless the gate set is exactly M1-ACC-01 through M1-ACC-16 with no duplicate, missing, skipped or unknown gate.

The committed evidence summary references raw CI artifacts by run identity/digest and records limitations explicitly.


## 23. M2 acceptance framework

Normative contract: `.work/milestones/M2.md`.

### 23.1 Executable-gate rule

The M1 lesson applies from the start of M2. An M2 gate is executable only when its owning slice provides:

1. an evidence producer;
2. an independent verifier where required;
3. a deterministic execution path;
4. a negative/falsification test.

An artifact schema becomes canonical together with its owning producer and verifier. M2-A therefore defines only the cross-slice `m2-scenario-v1` and `m2-run-manifest-v1`; subsystem artifacts (`route-events-v1`, `failure-decision-events-v1`, `recovery-budget-events-v1`, `delivery-binding-events-v1`, `provider-fault-events-v1`, `fault-harness-events-v1`, `network-calibration-v1`) are added by their owning slices.

### 23.2 Foundation gates

| ID | Name | Contract |
| --- | --- | --- |
| M2-ACC-01 | Scenario Identity | a resolved stochastic scenario has canonical identity and a persisted seed |
| M2-ACC-02 | Fault Attribution | each injected fault has exactly one primary fault plane |
| M2-ACC-03 | Route Privacy | VPN disappearance never leads to automatic direct-route external fetching |
| M2-ACC-04 | Persisted Media Independence | route or delivery-binding change never invalidates valid persisted media identity |
| M2-ACC-05 | Failure Separation | evidence separately represents observation, classification and recovery decision/action |
| M2-ACC-06 | Bounded Recovery Lineage | one RecoveryChain never receives implicit fresh budget across broker/Media3/route/refresh boundaries |
| M2-ACC-07 | Mutable Binding Independence | delivery binding can be refreshed without changing stable work identity; incompatible rebinding fails closed |

Later M2 slices may add gate IDs; no fixed count is reserved.

M2-A defines these contracts and their host reference/falsification tests (`scripts/measurement/tests/test_m2_contracts.py`, run by `Verify`). It does not claim that Android/runtime behavior passes M2-ACC-03..07; that proof belongs to the owning slices and to the M2-H canonical aggregation.

M2-B adds `route-events-v1` and `route-verification-summary-v1`. `Verify` runs the Kotlin reducer/policy tests, verifies the scripted host artifacts with the independent oracle (`scripts/ci/verify-m2-b-route-evidence.sh host`) and runs `test_m2_route_oracle.py`; Android Smoke (API 36) and Android Compatibility (API 34) verify the exported emulator artifact; API 23 is an instrumentation-only compatibility proof of the legacy source. M2-B evidence is a **component** proof for M2-ACC-03: it never claims that a real media fetch paused across an actual VPN/default-route transition (M2-F).

M2-C adds the recovery artifacts and makes **M2-ACC-05** and **M2-ACC-06** runtime-executable:

| Artifact | Producer | Independent verifier | Owning slice |
| --- | --- | --- | --- |
| `failure-decision-events-v1` | Kotlin `RecoveryCoordinator` via `RecoveryEvidenceRecorder` (`RecoveryEvidenceHostTest`, `RecoveryOriginAndroidTest`) | `scripts/measurement/m2_recovery_oracle.py` | M2-C |
| `recovery-budget-events-v1` | same | same | M2-C |
| `bridge-events-v2` | PlaybackBridge | schema + version-aware M1 bridge verifier | M2-C |
| `fetch-events-v4` | FetchBroker | schema + recovery oracle / version-aware M1 verifiers | M2-C |
| `recovery-verification-summary-v1` | `m2_recovery_oracle.py` | schema + `test_m2_recovery_oracle.py` | M2-C |

The oracle never imports the production coordinator. It re-derives every classification, decision, executed action and backoff window from its own tables, replays each chain's ledger (monotonic, persistent dimensions, spent ≤ limit, no change except by an explicit charge), joins every REMOTE_ATTEMPT charge to exactly one FetchBroker
`ATTEMPT_STARTED` in versioned fetch evidence (v4 for M2-C) and, with a
Media Lab trace, to exactly one origin request. Target/range preflight failures
must have zero charge, zero `ATTEMPT_STARTED` and zero origin request. M2-ACC-05 passes when at least one real/synthetic owner failure has independently verified observation, classification, decision and executed action; M2-ACC-06 passes when at least one chain spans several owner lifetimes on one ledger with an exact physical request count and no implicit reset. `Verify` runs the Kotlin tests, `scripts/ci/verify-m2-c-recovery-evidence.sh host` (seven scripted cases; both gates must pass in at least one case) and the falsification suite; Android Smoke (API 36) runs `RecoveryOriginAndroidTest` against Media Lab N4R and verifies the exported artifacts against the origin trace (`verify-m2-c-recovery-evidence.sh device`, both gates required). M1 Recovery remains a mandatory regression gate for the retry-ownership change. M2-C does not prove provider delivery-binding refresh, `Retry-After` behavior, real VPN/default-route fetch suppression, packet/network fault attribution, or transport superiority.

### 23.3 Additional M2 evidence rules

- a stochastic NETWORK fault without a persisted seed invalidates the run;
- route/VPN evidence requires a media path through Android's actual default network (`mediaPath = ANDROID_DEFAULT_NETWORK`); `adb reverse` and host-only paths are rejected;
- clock domains `ANDROID_MONOTONIC`, `HOST_MEDIA_LAB_MONOTONIC`, `HOST_FAULT_MONOTONIC` and `PROVIDER_WALL_CLOCK` are never subtracted or ordered across each other;
- portable evidence never retains signed URLs, cookies, `Authorization`, PO tokens, visitor/session secrets, VPN credentials, SSID/BSSID, or raw IPs unless a dedicated diagnostic experiment requires them;
- a live provider is never a deterministic acceptance oracle;
- transport comparisons follow the M2 experiment contract (only the transport backend differs); synthetic throughput alone never selects a transport.
