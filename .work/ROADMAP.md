# SpongeTube Roadmap v0.1

Status: **Provisional**
Date: **2026-09-21**

The roadmap is ordered to prove the risky assumptions before building a large YouTube UI.

## M0 — Architecture & Evidence Bootstrap

Goal: make the repository capable of rejecting bad ideas with evidence.

Deliverables:

- `.work` authority and product contract;
- architecture v0.1;
- benchmark/verification specification;
- Android project bootstrap;
- deterministic media fixtures;
- benchmark result schema;
- CI skeleton;
- initial ADR mechanism.

Exit:

- project builds on target API 36;
- benchmark harness can run at least N0/N1/N4;
- results are reproducible and machine-readable.

## M1 — Persistent Playback Core

Goal: prove one-fetch playback + durable reserve without YouTube dependency.

Build:

- MediaAsset / TrackVariant / FetchUnit;
- CoverageIndex + PlayableCoverage;
- ExtentStore interface;
- FetchBroker single-flight;
- PlaybackBridge to Media3;
- DeadlineScheduler;
- deterministic origin adapter;
- process-kill recovery.

Exit:

- no duplicate fetch in correctness suite;
- cached seek works;
- injected outage is stall-free whenever reserve covers the outage;
- persistent coverage survives restart.

## M2 — Network Resilience

Goal: treat bad connectivity as the normal environment.

Build:

- RouteHealthMonitor;
- FailureClassifier;
- DescriptorRefresher contract;
- RequestBudget/retry policy;
- VPN route policy;
- Cronet and OkHttp transport candidates;
- network impairment suite N0-N11.

Exit:

- route changes do not invalidate persisted media;
- VPN disappearance follows defined privacy policy;
- 403/429/expiry simulations have deterministic recovery;
- transport choice is backed by benchmark evidence, not preference.

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
