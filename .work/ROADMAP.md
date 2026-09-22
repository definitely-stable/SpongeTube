# SpongeTube Roadmap v0.1

Status: **Provisional**
Date: **2026-09-21**

The roadmap is ordered to prove the risky assumptions before building a large YouTube UI.

## M0 — Reproducible Android & Media Evidence Bootstrap

Status: **Complete — 2026-09-21**

Goal: create the smallest Android/media laboratory capable of falsifying later SpongeTube architecture and performance claims.

Canonical milestone specification: `.work/milestones/M0.md`.

M0 is split into focused, reviewable deliveries:

- **M0-A — Build & Repository Foundation**: pinned Android/Gradle toolchain and minimal `:app` only; later modules appear when their M0 work item actually needs them.
- **M0-B — Deterministic Media Lab**: separate data/control listeners, synthetic VOD fixtures, Range/DASH serving, deterministic N0/N1/N4 delivery, resolved scenario identity, request/session traces and fixture conformance evidence.
- **M0-C — Media3 Baselines**: Direct Media3 and standard CacheDataSource/SimpleCache reference paths.
- **M0-D — Measurement Harness**: structured playback/network metrics, cross-domain request correlation, run-manifest/result schema, Macrobenchmark and versioned Perfetto summary extraction.
- **M0-E — CI, Testing & Supply Chain**: stable `verify`/`android-smoke` checks, SHA-pinned Actions, emulator correctness automation and reproducible artifacts.
- **M0-F — Acceptance Evidence**: calibrated N0/N1/N4 Direct/Standard-Cache matrix on a deterministic API 36 emulator, explicit COLD/WARM cache observations, raw versioned artifacts, and no representative performance claim.

M0 explicitly does **not** implement Sponge FetchBroker, PlayableCoverage, Smart Buffer policy, a custom persistent store, YouTube extraction, Room/KSP/DI, Shorts, Live, TV or iOS.

Exit:

- clean clone builds on the pinned API 36 toolchain;
- deterministic F0/F1 media fixtures have provenance and SHA-256;
- Direct and Standard Cache Media3 baselines play the same controlled content;
- N0/N1/N4 are reproducible, scenario-hashed and calibrated before comparison;
- every benchmark run has a versioned manifest tying build, fixture, scenario, playback mode, cache state and device/runtime state together;
- CI has stable `verify` / `android-smoke` executable checks and reproducible artifacts;
- M0 acceptance is emulator-only and validates correctness/reproducibility, not representative device performance;
- emulator timing/resource observations are diagnostic only and cannot validate a product performance claim.

## M1 — Persistent Playback Core

Goal: prove one-owner fetch → crash-consistent durable extent → published playable coverage → Media3 playback, without YouTube dependency.

Canonical milestone specification: \`.work/milestones/M1.md\`.

M1 is split into focused deliveries:

- **M1-A — Contract & Evidence Foundation**: freeze PlayableCoverage/DurablePlayableReserve semantics, extent lifecycle, fixed seed protocol, M1 artifact schemas and persistence implementation choice.
- **M1-B — Durable ExtentStore**: immutable app-private extent files, SHA-256 integrity, same-filesystem temp/rename publication barrier, metadata transactions and restart recovery.
- **M1-C — CoverageIndex & Fixed Seeds**: interval algebra over published required-track coverage, deterministic S0/S10/S30/S60/S120 seeds, negative/holed seeds and independent coverage reconstruction.
- **M1-D — FetchBroker SingleFlight**: one physical owner fetch per FetchKey, multi-consumer join, reference-counted cancellation, priority escalation without restart and explicit duplicate-byte accounting.
- **M1-E — PlaybackBridge**: local reads from published Sponge coverage; all remote misses routed through FetchBroker; cached seek and in-flight join without a second Media3 upstream owner.
- **M1-F — Restart & N4 Recovery**: process-death recovery plus N4R-SHORT/N4R-EXHAUST/N4R-RESTORE; bounded retry ownership and post-restore continuation.
- **M1-G — Canonical Acceptance Evidence**: execute the normative M1 correctness matrix and publish independent cross-checked evidence.

M1 deliberately does **not** implement adaptive Smart Buffer policy, VPN/default-route recovery, YouTube descriptor refresh, transport ranking, packed storage, production GC/eviction, physical-device performance claims or background Keep Offline scheduling.

Exit:

- PlayableCoverage is reconstructed from \`PUBLISHED + VALID\` required-track extents and never from raw cache byte count;
- DurablePlayableReserve is contiguous from the current playhead and ends at the first required-track hole;
- no uncommitted/partial/corrupt/missing extent contributes playable coverage;
- fixed semantic coverage seeds are independently verified before playback;
- a FetchKey has one physical remote owner fetch even when playback and reserve consumers overlap;
- cached seek works without remote fetch;
- process death cannot create phantom coverage and valid published coverage survives restart;
- N4 outage shorter than durable reserve is survived without reserve-exhaustion rebuffer;
- N4 outage longer than reserve may stall but recovers after transport restoration without corrupting or refetching already valid coverage;
- runtime CoverageIndex and independent post-run reconstruction agree on interval semantics;
- emulator timing remains diagnostic only and is not used for representative performance claims.

## M2 — Network Resilience

Goal: treat bad connectivity as the normal environment while keeping failure attribution explicit.

Build:

- RouteHealthMonitor;
- FailureClassifier;
- DescriptorRefresher contract;
- RequestBudget/retry policy;
- VPN/default-route policy;
- transport evaluation driven by the M0 baseline;
- layered fault harness rather than one universal emulator:
  - delivery faults stay in Sponge Media Lab;
  - transport faults use a separate TCP-stream injector/proxy where justified;
  - packet/network faults use a scoped emulator such as `tc/netem` or an evidence-backed alternative;
  - provider faults (403/429/expiry) remain deterministic HTTP semantics;
  - Android VPN/default-route tests traverse Android's actual selected network rather than `adb reverse`;
- explicit seed persisted for every stochastic network scenario;
- network impairment suite N2/N3/N5–N11.

Do not apply netem globally to host loopback when that would also distort ADB/control traffic. Fault injection must be scoped to the media path.

Exit:

- route changes do not invalidate persisted media;
- VPN disappearance follows the defined privacy policy;
- 403/429/expiry simulations have deterministic recovery;
- transport/network faults can be attributed to their owning layer;
- stochastic scenarios are reproducible from their persisted seed;
- transport choice is backed by playback/device evidence, not synthetic throughput preference.

## M3 — YouTube Adapter Feasibility

Goal: prove current YouTube VOD compatibility without contaminating Sponge Core.

Build:

- isolated YouTube adapter;
- PlaybackPlan mapping;
- anonymous-first VOD resolve;
- separate A/V track handling;
- URL/descriptor refresh;
- adapter diagnostics and feature flags;
- compatibility smoke suite.

Explicitly excluded:

- account login;
- Shorts;
- Live;
- comments;
- TV.

Exit:

- representative VOD set starts reliably enough to continue product work;
- failures are classified;
- core tests pass unchanged when adapter changes;
- project license decision is compatible with any third-party extractor dependency actually used.

M1/M2 and the YouTube feasibility spike may overlap in calendar time, but production coupling happens only after both sides have evidence.

## M4 — Smart Buffer Policy

Goal: convert raw persistent caching into the core product advantage.

Build:

- ReserveController;
- Smart/Data Saver/Resilient modes;
- metered controls;
- ResourceGovernor;
- retention classes;
- waste accounting;
- visible Playable Reserve.

Exit:

- N3/N4 show material stall reduction versus direct Media3;
- prefetch waste is measured;
- no automatic "download entire video after N seconds" rule exists;
- resource policy is benchmarked.

## M5 — Android Product UX

Goal: make resilience feel invisible and understandable.

Build:

- Compose/Material 3 shell;
- Home/search/video detail for VOD;
- player;
- history/library;
- Keep Offline;
- cache/storage management;
- network recovery UI;
- diagnostics screen separated from normal UX;
- RU/EN localization foundations.

UX acceptance:

- tap-to-first-frame path contains no download ceremony;
- route loss does not throw the user out of playback while reserve exists;
- reserve/offline state is understandable without technical terminology;
- no Shorts surfaces exist.

## M6 — Offline & Background Hardening

Goal: make retained media reliable under Android lifecycle constraints.

Build:

- durable Keep Offline jobs;
- WorkManager/Media3 download scheduling where appropriate;
- storage quotas;
- eviction;
- atomic recovery;
- long-duration/process-death tests;
- battery/thermal hardening.

Exit:

- background behavior complies with current Android limits;
- user intent and notifications are clear;
- no indefinite speculative foreground service.

## M7 — YouTube Client Completeness

Only after engine quality is proven:

- subscriptions/local import;
- playlists;
- captions;
- SponsorBlock;
- DeArrow/RYD where product-appropriate;
- PiP/background playback;
- better recommendation/feed UX.

Account login remains a separate decision and threat/risk review.

## M8 — Release Hardening

- physical-device benchmark matrix;
- crash/ANR work;
- accessibility;
- privacy/security review;
- provider breakage playbook;
- reproducible release pipeline;
- release evidence report.

## Deferred roadmap

Do not schedule until Android VOD product has evidence of value and stability:

- iOS;
- Android TV;
- LAN casting;
- Live/DVR;
- generic provider/plugin ecosystem.

Shorts are intentionally excluded from the product direction unless explicitly reconsidered.
