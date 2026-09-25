# Evidence: M2-B default-route observation and session privacy policy

Date: **2026-09-25**

## Question

Does the M2-B runtime (`core.engine.route`) observe Android's default route,
reduce it and decide external-fetch eligibility exactly as
`.work/milestones/M2.md` section 8 specifies, on the API 23 legacy source and
on the API 24+ default-network callback, with evidence an independent host
oracle can replay?

## Hypothesis

For every recorded route signal, the production reducer's state, `routeEpoch`
and disposition, and the session guard's decisions, match an independent
replay (`scripts/measurement/m2_route_oracle.py`). The Android adapter
registers exactly one platform observer and leaks none, uses the API-correct
source, and never reports a capability below its API floor.

## Build

- PR head verified: `9d369bf02e314db051d75b7965002d7d2915bc39` (PR #78)
- `main` base: `5f3f03d087746f747d5c764c3b4ed09877d6ff03` (#77, route contract correction)
- Variant: `debug` (`:core:engine`), Gradle 9.6.0, AGP 9.4.0, Kotlin 2.2.10, JDK 17

## Environment

- Host: GitHub-hosted `ubuntu-24.04` runner
- Android: CI emulators API 23 (`sdk_google_phone_x86_64`), API 34, API 36
- Emulator default network: whatever the emulator provides. No VPN and no route switch were induced.

## Fixture

Not applicable: no media is fetched. Host cases are scripted platform-signal
sequences in `RouteEvidenceHostTest`.

## Network profile

Not applicable: M2-B injects no network fault.

## Baseline

The M2-A reference oracle (`m2_contracts.py`, as corrected by #77) is the
semantic baseline. The route oracle reuses its guard/decision function and
owns an independent route state machine.

## Procedure

- Verify: `bash scripts/ci/verify-m2-b-route-evidence.sh host build/m2-b-route` reruns `RouteEvidenceHostTest` and verifies each case with `m2_route_oracle.py verify --expected-api <api>`. `python3 -m unittest discover -s scripts/measurement/tests` runs `test_m2_route_oracle.py`.
- Android Smoke (API 36) and Android Compatibility (API 23/34): `:core:engine:connectedDebugAndroidTest`, then `verify-m2-b-route-evidence.sh device <out> <api>`. This requires all four `AndroidDefaultRouteMonitorTest` cases to pass and, on API >= 34, verifies the exported `route-events-v1` with the oracle.

## Results

| Check | Run | Result |
| --- | --- | --- |
| Verify | 36103695384 | PASS: host `route-events-v1` PASS for API 23 (8 events / 5 policy / 3 epochs), 24 (14 / 3 / 3), 28 (8 / 7 / 1), 34 (17 / 8 / 3), 36 (11 / 3 / 3) |
| Windows verify | 36103695384 | PASS |
| Android Smoke API 36 | 36103695332 | PASS, including the M2-B route observation smoke step |
| Android Compatibility API 34 | 36103695559 (attempt 1) | PASS: 4/4 instrumentation cases; `route-events-v1 PASS: api=34 events=7 policyEvaluations=4 epochs=1` |
| Android Compatibility API 23 | 36103695559 (attempt 2) | PASS: 4/4 instrumentation cases; legacy source, no export (see limitations) |
| M1 recovery | 36103695437 | PASS (M1 unaffected) |

Attempt 1 of API 23 failed in `Prepare emulator` ("emulator API 23 did not
complete boot within 420s") before any test ran. It was re-run once, as
recorded on PR #78.

Schema IDs:

- `https://spongetube.invalid/schema/route-events-v1.schema.json`
- `https://spongetube.invalid/schema/route-verification-summary-v1.schema.json`

Test counts in this slice:

- Kotlin host tests: 41 (reducer 19, policy 11, monitor lifecycle 9, evidence producer 2).
- Android instrumentation: 4 cases, including 128 open/shutdown cycles as a registration-leak check.
- Python route oracle suite: 26 tests, of which 17 are the required falsification mutations.

## Raw artifacts

- `m2-b-route-host-evidence`: artifact 10849719862, SHA-256 `d78a6a83b3b054d7f2998fdf2979fc2d5fc23849fe5a87f2f2dd193961cf8039`
- `android-compat-api-34`: artifact 10850277017, SHA-256 `79a39fcb3a5803d10c80cf90b082c1bd4aae60d25edbc7bae7864cc12e4a4d3b`
- `android-compat-api-23`: artifact 10851468231, SHA-256 `1003026de094c088b23822cdab73c0baf057e043387f4e2e2b8daced6d8d84db`
- `android-smoke-evidence` (run 36103695332): includes the API 36 `m2-b-route/route-events.json` and its summary

## Decision

- **Accepted (Validated):** route observation, reduction and session privacy-policy semantics as specified in M2.md section 8, on API 23 (legacy snapshot source) and API 34/36 (default-network callback).
- **M2-ACC-03:** policy-component proof PASS. The canonical end-to-end gate is **not** passed by this evidence.
- **M2-ACC-04:** the route subsystem has no mechanism to mutate persisted media (it touches neither ExtentStore nor CoverageIndex). The cross-subsystem proof remains with M2-F/M2-H.

## Limitations

M2-B proves route observation, reduction and session privacy-policy
semantics. It does not prove that a real media fetch is paused during an
Android VPN/default-route transition. That end-to-end proof belongs to M2-F.

- No VPN or default-route switch was induced on any emulator. The emulator route evidence covers the actual default network only; transitions are covered by the scripted host cases.
- API 23 does not export `route-events-v1`: the legacy additional-test-output staging path is unavailable there (see `2026-09-21-m0-baseline.md`). API 23 proof is the passing instrumentation assertions (legacy source only, `suspended`/`blocked` UNKNOWN, lifecycle, no leak), not a host-replayed artifact.
- `ExternalFetchRouteDecision` is not wired into FetchBroker (M2-C/M2-F). Nothing in production opens the monitor yet.
- Emulator timing is diagnostic only.
