# ADR-0003: Centralize recovery ownership in RecoveryCoordinator

Status: **Accepted**
Date: **2026-09-25**

## Context

M2 turns bad connectivity into the normal operating environment
(`.work/milestones/M2.md`). M2-A froze that observation, classification,
decision and action are separate (F-05), that recovery is bounded (F-06) and
that no internal layer transition grants fresh budget (F-07). M2-A left the
runtime owner, the classification enum, the budget form and the limits to
M2-C.

The M1 runtime (`main @ 1f060de`) has three layers that each decide to try
again:

```text
HttpRangeFetchExecutor   FetchAttemptDisposition.Failure(kind, retryable)
FetchBroker              for (attempt in 1..maxAttemptsPerFetch = 2)
Media3                   SpongeLoadErrorHandlingPolicy(maxRetries = 3)
```

Each Media3 retry reopens the DataSource, which creates a fresh FetchBroker
owner with a fresh broker budget. The theoretical upper bound for one
immutable FetchUnit is `2 broker attempts x 4 Media3 loads = 8` physical
requests, and nothing ties them to one budget. The executor also conflates a
received HTTP 408/429/5xx with a retryable transport failure, although a
received status is a PROVIDER-plane observation (M2.md 6, F-14).

Later slices add more retry-shaped actions: route wait (M2-F), delivery
binding refresh and `Retry-After` (M2-D). Without one owner each would add
another multiplier.

## Decision drivers

- one provable RecoveryBudget per immutable work item (M2-ACC-06);
- observation / classification / decision / action separation (M2-ACC-05);
- no request storm from `broker x Media3 x provider x route` multiplication;
- the chain must survive FetchBroker owner replacement, Media3 reopens and
  route waits;
- preserve every M1 invariant: single remote owner, bounded requests, no
  hidden Media3 upstream, publication barrier, cancellation barrier;
- no public API growth; Core stays independent of Media3 and Android route
  types.

## Options considered

### Option A — keep layered retry

Broker retries, Media3 retries and later provider retries stay where they are.

Benefits:
- no refactor.

Costs/risks:
- multiplicative amplification; each layer resets its own counter;
- a single RecoveryBudget cannot be proven because no layer sees all
  attempts.

Rejected.

### Option B — Media3 as retry owner

`LoadErrorHandlingPolicy` decides every retry.

Benefits:
- one existing policy object.

Costs/risks:
- Media3 `errorCount` is the number of errors of one load task; a
  RecoveryChain is about immutable media work and must survive player and
  DataSource boundaries (reserve demand has no Media3 load at all);
- Media3 cannot see route waits, broker ownership or provider refresh.

Rejected.

### Option C — FetchBroker as the only retry owner

Benefits:
- the broker already owns physical requests.

Costs/risks:
- too narrow: route wait and delivery refresh live above transport, and the
  chain must survive broker owner replacement (a cancelled owner, a late
  demand after the cancellation barrier);
- the broker registry lives for one physical owner, not for a logical
  recovery.

Rejected as the only layer.

### Option D — RecoveryCoordinator above FetchBroker

```text
Playback / Reserve demand
        |
 RecoveryCoordinator  (one RecoveryChain per immutable FetchKey)
        |
    FetchBroker       (one physical owner = one attempt)
```

Benefits:
- one owner sees every attempt of one work item and charges one ledger;
- the broker keeps single-flight physical ownership and the publication
  barrier; the executor only reports what it observed;
- M2-D/M2-F add actions and gates to the same chain without new budgets.

Costs/risks:
- a new internal runtime component with its own cancellation barrier;
- M1 evidence that assumed a multi-attempt broker owner, a direct broker
  consumer per read session or Media3 retries must be re-read (see
  Consequences).

Accepted.

## Decision

Option D, implemented in `io.github.definitelystable.spongetube.core.engine.recovery`
(all `internal`, no new public API):

- **RecoveryCoordinator is the only logical retry owner.** FetchBroker,
  HttpRangeFetchExecutor and Media3 never decide to try again.
- **RecoveryChain** (`recovery-1`, `recovery-2`, ... per runtime; not
  persisted) is one logical recovery of one immutable `FetchKey + ExtentSpec`.
  One open chain per FetchKey; the same FetchKey with a different ExtentSpec
  fails closed. Consumers (`RESERVE`, `PLAYBACK`) join the chain; the
  coordinator is the only broker consumer (`recovery:<chainId>:<ownerOrdinal>`).
  Lifecycle: ACTIVE, WAITING_BACKOFF, WAITING_ATTEMPT_PERMIT, CANCELLING,
  TERMINAL with SUCCESS | TERMINAL_FAILURE | BUDGET_EXHAUSTED |
  NO_REMAINING_DEMAND | SESSION_TERMINATION.
- **One FetchBroker owner = one physical attempt.** `FetchAttemptBudget` and
  `PlaybackBridgeConfig.maxAttemptsPerFetch` are removed (no public
  replacement). `FetchOutcome.attempts` stays as an M1 compatibility field
  (0 or 1).
- **Executor reports observations.** `FetchAttemptDisposition.Failure` carries
  a typed `FailureObservation` and a transport correlation id; no
  `retryable`. A received HTTP status is `HttpResponse` (PROVIDER plane).
  Exception messages are never inspected or retained.
- **FailureClassifier** (pure) maps observations to the M2-C vocabulary;
  **RecoveryPolicy** (`sponge-recovery-v1`) decides; the coordinator executes
  and records the executed action separately.
- **RecoveryBudget** is an open dimension vector. `sponge-recovery-v1`
  declares `REMOTE_ATTEMPT = 4` (1 initial + at most 3 retries). Transport
  preflight (target/range validation) occurs before admission. The charge and
  `ATTEMPT_STARTED` happen only after preflight succeeds and immediately
  before physical I/O, so a local preflight failure consumes zero remote
  budget. The ledger is never reset.
- **Backoff**: exponential with full jitter, `window = min(5000, 500 *
  2^(n-1))`, `delay = uniform(0, window)`; no delay before the first request;
  cancellable `delay()`; jitter and sleeper injectable.
- **RecoveryAttemptGate** is the wait seam before every attempt (default
  `ALWAYS_PERMIT`); waiting charges nothing and is event-driven. M2-F plugs
  route observation in here.
- **Provider actions fail closed until M2-D.** `WAIT_UNTIL_PROVIDER`,
  `REFRESH_DELIVERY_BINDING` and `RERESOLVE_PROVIDER` are decided but executed
  as `FAIL_CLOSED_ACTION_UNAVAILABLE`, never as a generic retry.
- **Media3 never retries a Sponge-managed load**:
  `SpongeLoadErrorHandlingPolicy.getRetryDelayMsFor` returns `C.TIME_UNSET`,
  `getMinimumLoadableRetryCount` returns 0, no fallback; the old
  `Config(maxRetries, retryDelayMs)` is removed.
- **STORAGE_CONFLICT** is local reconciliation (refresh the CoverageIndex and
  resolve the immutable extent) before any network action, now owned by the
  chain instead of the read session.

No circuit breaker, global retry token bucket or provider throttling is
added: they are cross-chain policies without M2-E evidence that they are
needed, and a global quota could let one failing video starve unrelated
playback. They remain follow-up candidates.

## Consequences

Positive:
- at most 4 physical requests per RecoveryChain instead of an unbounded
  product of layer limits; every request has exactly one ledger charge;
- classification separates transport, provider, response-contract, storage
  and local observations; 429 and bare 403 anchors are structural;
- M2-D/M2-F extend the same chain (new dimensions, handlers, gate) without a
  new budget architecture.

Negative:
- the M1 inner retry that retried a transient failure without a Media3
  reopen is gone; the chain's backoff replaces it. Under a long body stall
  (N4R) the chain gives up after 4 attempts (about 4 x readTimeout) where M1
  could tolerate up to 8.
- `PlaybackBridgeConfig` loses `maxAttemptsPerFetch` (opt-in `@SpongeBridgeApi`
  surface; the only callers are the bridge adapter tests and the benchmark
  harness).

Operational/recovery implications:
- `PlaybackBridgeRuntime.shutdown()`: coordinator shutdown (every chain ends
  as SESSION_TERMINATION, no new attempt) -> FetchBroker shutdown;
- the last consumer leaving closes the chain's broker lease; the chain stays
  registered until the physical owner is terminal (M1 cancellation barrier)
  and continues with the same ledger if demand returns;
- the broker completes an owner that was cancelled before its body ran, so
  a released lease can always observe a terminal outcome.

M1 evidence re-read (M1 invariants unchanged):
- `m1_bridge_evidence.py` E3 accepts the recovery-owned broker consumer when
  `case.json` binds the reserve lease to that owner's fetchId;
- `m1_recovery_evidence.py` accepts an M2-C case (`recoveryPolicyId`,
  `recoveryRemoteAttemptLimit`) whose inner bounds are the degenerate ones
  (`maxAttemptsPerOwner = 1`, `media3MaxRetries = 0`) and bounds owners per
  work item by the chain limit;
- historical `fetch-events-v3` remains immutable and keeps the exact M1
  outcome vocabulary. New M2-C runtime evidence uses `fetch-events-v4`,
  which adds the raw typed observation alongside the compatibility outcome;
  this prevents a new 429 row from being semantically interpreted as a
  transport failure while retained M1 v3 evidence remains readable.
- historical `bridge-events-v1` remains immutable with its RUNNING-owner JOIN
  meaning. New M2-C runtime evidence uses `bridge-events-v2`, carries
  `recoveryChainId` / recovery disposition explicitly, and never substitutes
  a RecoveryChain id into `fetchId`.

## Verification

- JVM: `FailureClassifierTest`, `RecoveryBudgetTest`, `RecoveryPolicyTest`,
  `RecoveryCoordinatorTest` (single-owner physical bound against a real HTTP
  server, full failure path, cancellation barrier, late success, gate waits,
  race stress), `FetchBrokerTest` (one attempt per owner, admission, priority
  raise, cancel-before-start), `SpongeLoadErrorHandlingPolicyTest`.
- Host oracle: `scripts/measurement/m2_recovery_oracle.py` re-derives every
  classification, decision, action and ledger transition from
  `RecoveryEvidenceHostTest` artifacts
  (`scripts/ci/verify-m2-c-recovery-evidence.sh host`, `Verify`);
  falsification suite `scripts/measurement/tests/test_m2_recovery_oracle.py`.
- Android API 36: `RecoveryOriginAndroidTest` against Media Lab N4R, verified
  with the origin trace (`verify-m2-c-recovery-evidence.sh device`,
  `Android Smoke`).
- Regression gates: `M1 Recovery`, `Android Compatibility` (API 23/34).
- Evidence record: `.work/evidence/2026-09-25-m2-c-recovery-chain.md`.

## Supersession

Supersedes in part ADR-0002: only its bounded Media3 retry policy
(`SpongeLoadErrorHandlingPolicy` retries at most `maxRetries` times). The
PlaybackBridge Media3 seam of ADR-0002 stays Accepted.

## Canonical-doc impact

- `.work/ARCHITECTURE.md`: recovery flow (RecoveryCoordinator above
  FetchBroker).
- `.work/milestones/M2.md`: M2-C section and the PROVISIONAL items it closes.
- `.work/VERIFICATION.md`: `failure-decision-events-v1`,
  `recovery-budget-events-v1`, `recovery-verification-summary-v1`; M2-ACC-05
  and M2-ACC-06 executable.
- `.work/PRODUCT.md`, `.work/ROADMAP.md`: no change beyond the M2-C status.
