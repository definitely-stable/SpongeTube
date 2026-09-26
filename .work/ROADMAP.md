# SpongeTube Roadmap v0.1

Status: **Normative sequencing**
Date: **2026-09-23**

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
- **M1-B2 — ExtentStore Hardening & Read Surface**: close audit-confirmed durability/read-path gaps needed by PlaybackBridge: explicit metadata durability policy/evidence, safe opaque read handles, storage-failure taxonomy and focused real-Room/reopen tests.
- **M1-V — Executable Independent Evidence Kernel**: real filesystem verifier, M1 artifact producers, executable host oracle and exact runtime-vs-oracle comparator. This is acceptance infrastructure, not product logic.
- **M1-C — CoverageIndex & Fixed Seeds**: asset-scoped in-memory coverage projection over published required-track coverage, deterministic S0/S10/S30/S60/S120 construction, negative/holed seeds and independent filesystem-backed reconstruction.
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
- M1 MUST gates used for slice closure have real evidence producers, independent checks where required and deterministic negative tests;
- PlaybackBridge prerequisites include a stable ExtentStore read surface; no raw filesystem path is part of the public playback contract;
- emulator timing remains diagnostic only and is not used for representative performance claims.

## M2 — Network & Provider Resilience

Status: **Active — M2-A, M2-B, M2-C and M2-D complete; M2-E next**

Goal: treat bad connectivity as the normal environment while keeping failure attribution explicit.

Canonical milestone specification: `.work/milestones/M2.md`.

M2 is split into focused deliveries (an ownership map, not a frozen API):

- **M2-A — Contract & Evidence Foundation** (#75, complete): contract/evidence only — FROZEN/PROVISIONAL/DEFERRED decisions, fault-plane ownership, route/privacy contract, RecoveryChain and budget invariants, stable identity vs mutable delivery binding, `m2-scenario-v1` / `m2-run-manifest-v1`, host falsification suite. No runtime change.
- **M2-B — Route Observation & Privacy Policy** (complete, `.work/evidence/2026-09-25-m2-b-route-observation.md`): Android default-route observation (API 24+ `registerDefaultNetworkCallback`, API 23 `CONNECTIVITY_ACTION` snapshot fallback), one serialized reducer, `DefaultRouteState`, per-session `SessionRouteGuard` and `ExternalFetchRouteDecision`, `route-events-v1`; API 23 and API 34/36 tested. No FetchBroker wiring (M2-C/M2-F).
- **M2-C — Recovery Chain, Failure Classification & Request Budget** (#79, complete, `.work/evidence/2026-09-25-m2-c-recovery-chain.md`, ADR-0003): `RecoveryCoordinator` as the only logical retry owner, RecoveryChain identity, typed `FailureObservation` + conservative `FailureClassifier`, `sponge-recovery-v1` (`REMOTE_ATTEMPT = 4`, exponential backoff with full jitter), `RecoveryAttemptGate` seam, one FetchBroker owner = one attempt, no Media3 retry; `failure-decision-events-v1`, `recovery-budget-events-v1`; M2-ACC-05/06 executable. Provider actions fail closed until M2-D; route gate wiring is M2-F.
- **M2-D — Delivery Binding Refresh & Deterministic Provider Fault Recovery** (#81, complete, `.work/evidence/2026-09-26-m2-d-provider-recovery.md`, ADR-0004): delivery binding revision, CAS/single-flight refresh, `sponge-recovery-v2`, `Retry-After`, N8/N9/N10 deterministic provider simulator, M2-ACC-07/08; #50 review recorded; production stale-binding signal unresolved.
- **M2-E — Transport / Packet Fault Harness**: external transport and scoped network fault infrastructure with seeded stochastic profiles.
- **M2-F — Android Route/VPN Recovery Integration**: end-to-end VPN/default-route recovery on Android's actual selected network.
- **M2-G — Transport Evidence Evaluation**: transport comparison under the controlled experiment contract.
- **M2-H — Canonical M2 Acceptance**: aggregation of already-working owning-slice evidence.

#50 is a review dependency for M2-D and the production provider seam; it does not block provider-independent M2-A/B/C work.

Build:

- default-route observation (`AndroidDefaultRouteMonitor` → `DefaultRouteState`) and per-session `SessionRouteGuard` (M2-B);
- FailureClassifier;
- DescriptorRefresher contract;
- provider-aware RequestBudget/retry policy;
- explicit URL/descriptor expiry and stale-descriptor outcomes;
- persistent partial-attempt/resume policy only after the selected delivery path proves a stable continuation identity;
- VPN/default-route policy;
- transport evaluation driven by the M0 baseline;
- layered fault harness rather than one universal emulator:
  - delivery faults stay in Sponge Media Lab;
  - transport faults use a separate TCP-stream injector/proxy where justified;
  - packet/network faults use a scoped emulator such as `tc/netem` or an evidence-backed alternative;
  - provider faults (403/429/expiry) remain deterministic HTTP semantics;
  - Android VPN/default-route tests traverse Android's actual selected network rather than `adb reverse`;
- explicit seed persisted for every stochastic network scenario;
- network impairment suite N2/N3/N5–N11, each resolved with `scenarioFamily + variant + primaryPlane` (N3 and N6 are plane-specific per variant; N11 covers only storage/recovery interaction, while quota/eviction policy is M6).

Do not apply netem globally to host loopback when that would also distort ADB/control traffic. Fault injection must be scoped to the media path.

Exit:

- route changes do not invalidate persisted media;
- VPN disappearance follows the defined privacy policy;
- 403/429/expiry simulations have deterministic recovery;
- transport/network faults can be attributed to their owning layer;
- stochastic scenarios are reproducible from their persisted seed;
- transport choice is backed by playback/device evidence, not synthetic throughput preference.

## M3 — YouTube Adapter

M3 has two deliberately different tracks. Feasibility starts early; production coupling remains later.

### M3-A — YouTube Delivery Feasibility Probe

Status: **parallel risk track — may run during M1**

Goal: determine the smallest viable anonymous long-form YouTube VOD delivery path and identify provider assumptions that Sponge Core must not accidentally freeze.

Probe, with bounded evidence:

- viable client/profile paths;
- HTTPS/DASH/HLS/SABR availability by tested case;
- split A/V versus muxed/interleaved playback requirements;
- PO-token/client/context/protocol requirements actually observed;
- whether a JS/challenge runtime is required for the selected path;
- descriptor/URL expiry and refresh semantics;
- range/continuation capability;
- seek/start/sustained-playback behaviour;
- provider rejection/throttling/backoff observations;
- dependency licensing and intended distribution constraints.

Deliverables:

- evidence record under `.work/evidence/`;
- compatibility matrix with test date and environment;
- ADR only for decisions that actually need to become architecture;
- explicit list of assumptions accepted, rejected or still unknown.

M3-A MUST NOT become a production extractor by stealth. It does not pre-commit the project to yt-dlp/NewPipe code, SABR implementation, a JS runtime, fixed expiry margins or traffic-shaping/pacing emulation without evidence.

**Sequencing gate:** M1-C's pure interval algebra may continue in parallel, but M1-D public fetch/transport contracts must not be frozen in a form contradicted by M3-A findings. Provider-specific protocol details still may not enter ExtentStore/CoverageIndex.

### M3-B — Production YouTube Adapter

Goal: implement the smallest production adapter justified by M3-A evidence without contaminating Sponge Core.

Build:

- isolated YouTube adapter;
- PlaybackPlan + PlaybackRequirementSet mapping;
- anonymous-first VOD resolve;
- capability negotiation for the actually required delivery paths;
- descriptor refresh using provider policy derived from evidence rather than a hard-coded global expiry margin;
- provider diagnostics and feature flags;
- compatibility smoke suite;
- only the token/challenge/SABR components proven necessary by M3-A.

Explicitly excluded:

- account login;
- Shorts;
- Live;
- comments;
- TV.

Exit:

- representative VOD cases resolve and start through the selected adapter path;
- sustained playback, seek and descriptor refresh are exercised;
- split or muxed requirements map into the same provider-independent core model;
- descriptor refresh does not change stable media/storage identity;
- failures are classified;
- core tests pass unchanged when adapter implementation changes;
- project licensing/distribution decision is compatible with every third-party component actually used.

Production coupling happens only after both the M1 core/evidence path and M3-A provider evidence support the chosen boundary.

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

## M6 — Offline, Storage & Background Hardening

Goal: make retained media reliable under Android lifecycle constraints.

Build:

- durable Keep Offline jobs;
- WorkManager/Media3 download scheduling where appropriate;
- storage quotas;
- eviction;
- explicit retention/pin schema and migrations owned here unless earlier product evidence requires a smaller prerequisite;
- ENOSPC/storage-pressure recovery and user-visible failure handling;
- incremental/background integrity verification and large-library startup scaling;
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

- final LICENSE and third-party license compatibility audit;
- provider Terms/distribution-channel review and documented release decision;
- dependency/SBOM/license report appropriate to the release channel;
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
