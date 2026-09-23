# M1-E PlaybackBridge implementation — 2026-09-23

Status: review-hardened M1-E implementation; merge is gated on the full PR CI
Issue: #40 (parent #35, blocks #41)
Decision record: `.work/adr/0002-playback-bridge-media3-seam.md`

## Question

Can Media3 play the F1 fixture reading only PUBLISHED + VALID coverage, with every remote media miss owned by FetchBroker, no hidden Media3 upstream and cached seek served locally?

## Hypothesis

With the M1-E bridge, (a) seeks inside published coverage produce zero broker attempts and zero origin data-plane requests, (b) every origin data-plane request in the run corresponds to exactly one completed broker attempt, and (c) runtime coverage after each case equals the independent oracle.

## Build

- Change set: PR #60 on `feat/40-playback-bridge` (base `main @ 33b5e84`), including post-review correctness hardening.
- Media3 1.11.1 (unchanged), DASH MediaSource, default Media3 buffer constants.
- Bridge runtime (Provisional): `maxAttemptsPerFetch=2`, connect timeout 10 s, read timeout 15 s.
- `SpongeLoadErrorHandlingPolicy` (Provisional): `maxRetries=3`, `retryDelayMs=1000`, retryable outcomes `RETRYABLE_TRANSPORT_FAILURE`, `TERMINAL_TRANSPORT_FAILURE`, `CANCELLED_NO_CONSUMERS`; no fallback selection.

## Environment

- Canonical: API 36 emulator in Android Smoke (`m0e-api36`).
- Local development machine (Windows) had no phone AVD; instrumented E1-E6 were **not** executed locally.

## Fixture

- F1 split video/audio DASH (18 video + 19 audio segments, separate init), MPD served from packaged assets verified against `test-fixtures/media/manifest.json` (length + SHA-256).
- Seeds reuse the shared F1 FetchUnit catalog (`:test-support:fixture-f1`), identical ExtentIds to M1-C.

## Network profile

- Media Lab N0, `--session-id=m1-e-bridge`, loopback via `adb reverse`.

## Procedure

```text
./gradlew :playback:bridge:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.spongetube.m1e.originBaseUrl=http://127.0.0.1:18080
bash scripts/ci/verify-m1-e-evidence.sh build/android-smoke/m1-e-evidence <media-lab requests.jsonl>
```

Cases:

| Case | Setup | Pass condition (host verifier) |
| --- | --- | --- |
| E1 / M1-ACC-09 | S120; play, seek 30 s and 60 s, pause until loading stops | no MISS/JOIN/WAIT_EXISTING between SEEK_ISSUED/SEEK_SETTLED (event sequence); no broker ATTEMPT_STARTED between the markers' broker watermarks; no fetch at all |
| E2 / M1-ACC-10 | S30; quiesce, then seek to 90 s | inside SEEK_TO_MISSING_ISSUED..SETTLED: MISS -> broker ATTEMPT_STARTED -> terminal SUCCESS -> LOCAL_SERVE for the same read/extent; origin request is correlated to the broker attempt |
| E3 / M1-ACC-08 | S30; harness RESERVE lease on video segment 4 held at the transport gate until the bridge JOIN | JOIN onto the reserve owner, one PRIORITY_RAISED, exactly one physical attempt |
| E4 | S10; video segment 2 planned as 3 ranged FetchUnits | ranged misses, mid-resource Range requests in the origin trace, one open read across ranged extents |
| E5 | S30_MISSING_INIT | exactly one video init MISS; published segment 1 served locally |
| E6 | S10; all attempts gated forever; `player.release()` during the blocked miss | blocked reads CLOSE and owners end `CANCELLED_NO_CONSUMERS` within 1 s; ExtentStore closes without `ExtentConflictException` |
| all | — | schema-valid rows; MISS/JOIN/WAIT_EXISTING correlate to a terminal broker owner; JOIN means a true RUNNING-owner join; no fetch of a pre-run committed extent; zero duplicate range bytes; origin data-plane requests ↔ completed broker attempts 1:1; zero manifest origin requests; post-run runtime coverage == `m1_oracle.py` reconstruction |

## Results

- Review hardening adds: fail-closed immutable ExtentSpec admission for same-ExtentId collisions; explicit NEW_OWNER/JOINED_RUNNING/WAITED_CANCELLING acquisition causality; Media3 DataSource EOF/range contract coverage; and a negative verifier test proving a pre-seek fetch cannot satisfy E2.
- Host/JVM and schema/verifier checks are owned by the PR Verify workflow. Instrumented E1-E6 plus the DataSource edge-range contract are owned by Android Smoke. This record does not claim a device PASS independently of those retained CI artifacts.

## Raw artifacts

Android Smoke artifact `android-smoke-evidence` → `build/android-smoke/m1-e-evidence/` (device evidence, origin trace, `verification-summary.json`, per-case oracle outputs).

## Decision

The PlaybackBridge seam (ADR-0002) is accepted for M1. Retry and timeout parameters remain Provisional until M1-F exercises them under N4.

## Limitations

- No performance, latency or resource claim; emulator timing is non-representative.
- A miss waits for the whole FetchUnit before the decoder receives bytes.
- Failed broker attempts carry no transport correlation; any origin request that is not matched to a completed attempt fails the run (conservative).
- Immutable committed metadata is matched against the playback plan before `openRead`. The handle itself still checks length only; same-length on-disk corruption after admission is caught by the next recovery scan, not at read time.
- The E3/E6 transport gate is an evidence hook: it only delays or cancels an attempt before the request is opened and cannot create one.
- Ranged units in E4 claim no media interval (conservative coverage) and exist to exercise Range fetch and boundary reads.
- N4 recovery, process death and bounded-retry proof under outage are M1-F.
