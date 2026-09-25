# Evidence: M2-C recovery chain, failure classification and request budget

Date: **2026-09-25**
Status: **Complete — final-head CI verification green**

## Question

Is `RecoveryCoordinator` the only logical retry owner, so that one
RecoveryChain per immutable work item spends one monotonic
`sponge-recovery-v1` ledger with exactly one REMOTE_ATTEMPT charge per
physical request, while failure evidence keeps observation, classification,
decision and executed action separate and independently verifiable
(M2-ACC-05, M2-ACC-06)?

## Hypothesis

- FetchBroker, the transport executor and Media3 never decide to try again;
- one chain never makes more than 4 physical requests and never resets its
  ledger across owners, backoff, priority escalation or attempt-gate waits;
- every charge maps to exactly one FetchBroker attempt and, with an origin
  trace, to exactly one origin request;
- an independent oracle re-derives every classification, decision and action.

## Build

- PR: #80
- Verified implementation head: `f6258d88dab2bd015022b1a15e2e7041bdca7898`
- Branch: `claude/m2-c-recovery-chain-6z7dp2`
- `main` base: `1f060dee95a1127e356b26285bc0def17d36b711` (#78, M2-B)
- Decision record: `.work/adr/0003-centralize-recovery-ownership.md`
- Policy: `sponge-recovery-v1`, `REMOTE_ATTEMPT = 4`, backoff
  `window = min(5000 ms, 500 ms * 2^(n-1))`, `delay = uniform(0, window)`
  (full jitter), no delay before the initial request
- Host test policy: `sponge-recovery-test-v1`, `REMOTE_ATTEMPT = 4`, windows
  100/200/400 ms, deterministic full-window jitter, recording sleeper

## Environment

- Authoring environment: Linux container, JDK 21, Python 3.11, **no Android
  SDK** (`dl.google.com` is not reachable from it).
- Kotlin JVM verification ran in a scratch Gradle 8.14.3 / Kotlin 2.2.10 JVM
  project that compiles the real `:core:engine` main and test sources, the
  real `:core:storage` model files (`ExtentModels.kt`, `ExtentReadHandle.kt`)
  and minimal stubs for `SystemClock`, `ExtentStore` and the Android/androidx
  test types used by `RecoveryOriginAndroidTest` (compile-only). It excludes
  `AndroidDefaultRouteMonitor.kt` and `DefaultRouteReducerTest.kt` (Android
  framework types; untouched by M2-C). This is not the repository Gradle/AGP
  build; the CI rows below are the authoritative build.
- `:playback:bridge`, `:app` and the Android instrumentation code were **not
  compiled** in this environment (Media3 and androidx artifacts come from
  `dl.google.com`).

## Fixture

- Host: scripted attempts over `FetchKey`s under `fixture:M2C/...`
  (`RecoveryEvidenceHostTest`, `RecoveryCoordinatorTest`).
- Android: F1 `segment-1-00001.m4s` (81 811 bytes, SHA-256 `08ac9353...`)
  served by Media Lab N4R.

## Network profile

- Host: no network; transient failures are scripted observations. The
  physical-request bound test uses a loopback `com.sun` HTTP server that
  answers 503 to every request.
- Android: Media Lab N4R manual media-body gate. Attempts 1 and 2 time out
  (`readTimeout = 1.5 s`) while the body gate is closed; the test's
  `RecoveryAttemptGate` opens the body gate before permitting attempt 3. No
  VPN or route switch.

## Baseline

M1 runtime at `1f060de`: FetchBroker `maxAttemptsPerFetch = 2`, Media3
`maxRetries = 3`, theoretical bound `2 x 4 = 8` physical requests per work
item across unrelated counters.

## Procedure

- Kotlin JVM tests (harness above): `./run.sh` → all engine unit tests
  including `FetchBrokerTest`, `PlaybackReadSessionTest`,
  `HttpRangeFetchExecutorTest`, `FailureClassifierTest`, `RecoveryBudgetTest`,
  `RecoveryPolicyTest`, `RecoveryCoordinatorTest`, `RecoveryEvidenceHostTest`.
- Host evidence: `RecoveryEvidenceHostTest` artifacts verified with
  `scripts/measurement/m2_recovery_oracle.py verify --case ...` for each case
  (equivalent to `verify-m2-c-recovery-evidence.sh host` minus its Gradle
  step, which needs the Android SDK).
- Host contracts: `python3 -m unittest discover -s scripts/measurement/tests -p 'test_*.py'`.
- CI: `Verify` (`verify-m2-c-recovery-evidence.sh host`),
  `Android Smoke` API 36 (`RecoveryOriginAndroidTest` + `verify-m2-c-recovery-evidence.sh device`
  against the Media Lab trace), `Android Compatibility` API 23/34,
  and `M1 Recovery`, all green on the verified implementation head.

## Results

### Local

| Check | Result |
| --- | --- |
| Kotlin JVM tests (harness) | 134 / 134 PASS; 5 consecutive full runs PASS |
| Race-sensitive classes (`RecoveryCoordinatorTest`, `RecoveryEvidenceHostTest`, `PlaybackReadSessionTest`, `FetchBrokerTest`) | 40 consecutive runs, 0 failures (after the fix below) |
| Python host tests | 266 / 266 PASS |
| `test_m2_recovery_oracle.py` | 44 tests: 36 falsification, 6 positive, 2 schema |
| New schemas in the fail-closed subset | PASS; every runtime-produced artifact validates |
| M2-A / M2-B / M0-M1 schema SHA guards | PASS (unchanged bytes) |

Host recovery cases (production coordinator, independent oracle):

| Case | Chains | Physical attempts = charges | Failures | Terminal | M2-ACC-05 | M2-ACC-06 |
| --- | --- | --- | --- | --- | --- | --- |
| transient-then-success | 1 | 3 = 3 | 2 | SUCCESS | PASS | PASS |
| budget-exhausted | 1 | 4 = 4 | 4 | BUDGET_EXHAUSTED | PASS | PASS |
| reserve-playback-join | 1 | 2 = 2 | 1 | SUCCESS | PASS | PASS |
| terminal-classifications | 8 | 8 = 8 | 8 | 7 TERMINAL_FAILURE, 1 SUCCESS (local reconciliation) | PASS | NOT_EXERCISED |
| cancellation-barrier | 1 | 2 = 2 | 1 | SUCCESS | PASS | PASS |
| attempt-gate-wait | 1 | 2 = 2 | 1 | SUCCESS | PASS | PASS |
| final-consumer-cancellation | 1 | 1 = 1 | 1 | NO_REMAINING_DEMAND | PASS | NOT_EXERCISED |

Full failure path (item 86, `transient-then-success`): one chain
`recovery-1`; owners `fetch-1`, `fetch-2`, `fetch-3`; REMOTE_ATTEMPT
`0→1`, `1→2`, `2→3`; two `READ_TIMEOUT` failures, each
`TRANSIENT_TRANSPORT` → `RETRY_AFTER_BACKOFF` → `SCHEDULE_BACKOFF`
(100 ms, 200 ms); terminal SUCCESS.

Exact physical attempt counts:

- `RecoveryCoordinatorTest.fourAllowedAttemptsProduceExactlyFourPhysicalOriginRequests`:
  the real `HttpRangeFetchExecutor` against a loopback server answering 503
  made exactly **4** origin requests (not 8 or 16) and ended
  BUDGET_EXHAUSTED under `sponge-recovery-v1`.
- `budget-exhausted`: 4 attempts, 4 charges, no fifth attempt.

Classification cases proven (host): READ_TIMEOUT, CONNECTION_RESET,
PREMATURE_EOF → TRANSIENT_TRANSPORT; 503 → PROVIDER_TRANSIENT_RESPONSE
(retried); 429 → PROVIDER_RATE_LIMITED → WAIT_UNTIL_PROVIDER →
FAIL_CLOSED_ACTION_UNAVAILABLE; bare 403 → PROVIDER_REJECTED → FAIL_TERMINAL;
302 → UNKNOWN → FAIL_TERMINAL; FULL_BODY_FOR_RANGE_REQUEST → RANGE_REJECTED →
FAIL_TERMINAL; descriptor stale → DELIVERY_BINDING_STALE →
REFRESH_DELIVERY_BINDING → FAIL_CLOSED_ACTION_UNAVAILABLE; ENOSPC →
STORAGE_FAILURE → FAIL_TERMINAL; integrity → CONTENT_INTEGRITY →
FAIL_TERMINAL; STORAGE_CONFLICT → PUBLICATION_CONFLICT →
RECONCILE_LOCAL_COVERAGE → LOCAL_COVERAGE_READY. Each terminal
classification made exactly one physical attempt.

### Defect found during verification

A repeated-run stress loop exposed a race in the first coordinator version:
when the last consumer released the chain's broker lease before the chain
awaited the owner, the broker `Handle.await()` rejected the released handle
and the chain ended as TERMINAL_FAILURE instead of NO_REMAINING_DEMAND (about
1 in 20 runs under load). Separately, a FetchBroker owner cancelled before its
coroutine body was dispatched never completed (latent since M1, made reachable
by M2-C). Fixes: `FetchHandle.awaitTerminal()` (observes the terminal after
release; the M1 cancellation barrier) and completion handlers that terminate
both a broker owner and a RecoveryChain driver cancelled before their bodies
run. Review hardening also moved REMOTE_ATTEMPT admission after transport
preflight, made concurrent RecoveryCoordinator shutdown callers await one
cleanup, introduced recovery-aware `bridge-events-v2`, and introduced typed
`fetch-events-v4` instead of reinterpreting historical schemas. Regression tests:
`RecoveryCoordinatorTest.lastConsumerLeavingAtAnyPointNeverFailsOrHangsTheChain`
(fails without the fix) and
`FetchBrokerTest.ownerCancelledBeforeItsBodyRunsStillReachesATerminal`.

### CI

| Check | Run | Result |
| --- | --- | --- |
| Verify (incl. `verify-m2-c-recovery-evidence.sh host`) | 36149635693 | PASS |
| Windows verify | 36149635693 | PASS |
| Android Smoke API 36 (incl. `RecoveryOriginAndroidTest`, expected 3 physical requests) | 36149635799 | PASS |
| Android Compatibility API 23 / 34 | 36149635545 | PASS |
| M1 Recovery | 36149635564 | PASS |

## Gates

- **M2-ACC-05 — Failure Separation:** PASS on host and Android device evidence.
- **M2-ACC-06 — Bounded Recovery Lineage:** PASS on host evidence and Android
  origin-trace correlation; charge = physical attempt = origin request for the
  canonical device proof.

## Schemas and verifier

- `.work/schemas/failure-decision-events-v1.schema.json`
- `.work/schemas/recovery-budget-events-v1.schema.json`
- `.work/schemas/bridge-events-v2.schema.json`
- `.work/schemas/fetch-events-v4.schema.json`
- `.work/schemas/recovery-verification-summary-v1.schema.json`
- examples: `.work/schemas/examples/m2/failure-decision-v1.example.json`,
  `recovery-budget-v1.example.json`, `recovery-fetch-events-v3.example.jsonl`,
  `recovery-verification-summary-v1.example.json`, `recovery/<case>/`
  (all produced by the Kotlin runtime)
- verifier: `scripts/measurement/m2_recovery_oracle.py` (no production import);
  falsification suite `scripts/measurement/tests/test_m2_recovery_oracle.py`
- CI: `scripts/ci/verify-m2-c-recovery-evidence.sh host|device`

## M1 regression reasoning

- E3 bridge evidence: the reserve lease is now joined to its owner through
  `case.json reserveFetchId` because the broker consumer is the chain
  (`m1_bridge_evidence.py`, 3 new tests).
- M1 Recovery: the provider records `maxAttemptsPerOwner = 1`,
  `media3MaxRetries = 0`, `recoveryPolicyId = sponge-recovery-v1`,
  `recoveryRemoteAttemptLimit = 4`; the verifier bounds owners per work item
  by the chain limit (3 new tests). The M1 invariants (single remote owner,
  bounded requests, no hidden Media3 upstream, publication and cancellation
  barriers, recovery after restore) are unchanged. Expected behavioral
  difference under N4R: a chain tolerates about `4 x readTimeout` of body
  stall before BUDGET_EXHAUSTED, where M1 tolerated up to 8 owner attempts.
  N4R-RESTORE/EXHAUST remained green in final-head M1 Recovery run 36149635564.

## Limitations

M2-C proves provider-independent failure classification, single-owner
recovery lineage and bounded remote-attempt accounting.

It does not prove provider delivery-binding refresh, Retry-After behavior,
real VPN/default-route fetch suppression, packet/network fault attribution,
or transport superiority.

Additional limitations of this record:

- The authoring container did not provide the Android/Media3 build environment;
  authoritative Android/Media3 proof is the green final-head repository CI listed above.
- `CONNECTION_RESET` means "socket error after connect"
  (`java.net.SocketException`); the errno is not observable without parsing
  exception messages, which M2-C forbids.
- No circuit breaker or global retry token bucket (deliberately; follow-up
  candidate after M2-E stress evidence).

## Conclusion

The hypothesis holds on independent host evidence and repository Android
evidence: one retry owner, one ledger per chain, charge = physical attempt,
at most 4 physical requests, no implicit reset, and separated failure layers
that an independent oracle re-derives. M2-C is complete.
