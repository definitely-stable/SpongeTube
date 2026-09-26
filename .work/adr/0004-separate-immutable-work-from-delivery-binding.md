# ADR-0004: Separate immutable media work from mutable delivery binding

Status: **Accepted**
Date: **2026-09-26**

## Context

M2 turns bad connectivity into the normal operating environment
(`.work/milestones/M2.md`). M2-A froze stable media identity (F-01), single
remote ownership (F-04), delivery-state separation (F-08), clock-domain
separation (F-11) and the HTTP 403/429 anchors (F-14). M2-C
([ADR-0003](0003-centralize-recovery-ownership.md)) centralized retry ownership
in `RecoveryCoordinator`, but left the provider actions `WAIT_UNTIL_PROVIDER`,
`REFRESH_DELIVERY_BINDING` and `RERESOLVE_PROVIDER` decided yet executed as
`FAIL_CLOSED_ACTION_UNAVAILABLE`.

The M2-C runtime has no provider-neutral representation of the mutable material
required to fetch already-known stable work. The delivery locator of the
deterministic laboratory is a static `HttpRangeTarget`: it carries no
signature, session, generation or expiry, so it cannot express a rejected but
rebindable delivery context. #50 (M3-A) has not yet produced provider findings,
so nothing YouTube-specific may be frozen by this decision.

Constraints carried into the decision:

- `FetchKey`/`ExtentSpec` stay immutable work identity; delivery material must
  not enter them (F-01, M2-I02);
- persisted valid media never becomes invalid because a locator, session or
  token changed (M2-I02);
- a new `SharedFetch`, a Media3 reopen, a route change or a delivery-binding
  refresh never grants fresh budget by itself (F-07);
- clock domains are never directly compared (F-11);
- a live provider is never a deterministic acceptance oracle (F-13).

## Decision drivers

- mutable delivery state isolated from stable identity and durable storage;
- one explicit, attributable refresh owner inside the existing RecoveryChain;
- exactly one budget charge per actual provider operation, none otherwise;
- a provider-neutral binding representation: a binding may be a locator set, a
  stateful session, continuation context or a transport conversation;
- evidence reconstructable without provider secrets;
- fail closed while provider-specific semantics are unknown.

## Options considered

### Option A — put the locator/revision into `FetchKey`/`ExtentSpec`

Benefits:
- no new component; a refreshed locator would be visible wherever work identity
  is.

Costs/risks:
- breaks F-01: a new URL would become new work, invalidating persisted coverage
  and re-downloading unchanged bytes;
- forces a storage migration for state that is not media identity;
- a token rotation would look like different media.

Rejected.

### Option B — let the transport/executor refresh URLs on its own

Benefits:
- refresh would happen closest to the request.

Costs/risks:
- a hidden retry owner below the RecoveryChain, with nothing bounding it;
- the refresh bypasses the `RecoveryBudget` and cannot be attributed to a
  failure;
- the executor would need provider policy, contradicting observation-only
  executors (ADR-0003).

Rejected.

### Option C — a URL-centric `UrlRefresher`

Benefits:
- simple for the current static-locator laboratory target.

Costs/risks:
- the binding may be a session, continuation state or a transport context, not
  a URL set;
- freezes a URL-shaped provider assumption before #50.

Rejected.

### Option D — provider-neutral `DeliveryBindingRevision` + `DeliveryBindingCoordinator` under `RecoveryCoordinator` ownership

Benefits:
- F-01/F-07/F-08 stay intact and no storage migration is needed;
- compare-and-set refresh with single-flight per revision;
- the chain keeps its ledger and decides whether a next owner starts.

Costs/risks:
- a new internal runtime component with its own cancellation barrier and a
  documented lock order (`DeliveryBindingCoordinator.lock` ->
  `RecoveryCoordinator.lock`).

Accepted.

## Decision

Option D, implemented in
`io.github.definitelystable.spongetube.core.engine.delivery` (all `internal`,
no new public API, no new module, no new dependency):

- **The binding revision is mutable execution state.** It is a local opaque id
  (`binding-N`), never a URL, token, URL hash or provider secret, and never
  part of `FetchKey`, `ExtentSpec`, `ExtentStore` or `CoverageIndex`. No storage
  migration is implied.
- **`DeliveryBindingSnapshot(revision, material)`** pairs the revision with the
  material. Material is provider-local and opaque to core; the deterministic
  HTTP laboratory target is only adapter material.
- **Every new FetchBroker owner selects the current snapshot.**
  `BINDING_SELECTED_FOR_ATTEMPT` binds each physical attempt to its revision;
  joining consumers never change the binding of an in-flight owner.
- **Refresh is compare-and-set and single-flight.** One operation per revision:
  `REFRESHED` | `ALREADY_ADVANCED` | `JOINED_REFRESH` | `INCOMPATIBLE` |
  `FAILED`, plus `NOT_ADMITTED` and `CLOSED`. There is no retry inside the
  refresher; retry ownership stays with the RecoveryChain.
- **The refresh budget belongs to the RecoveryChain.**
  `DELIVERY_BINDING_REFRESH` is charged via `DeliveryBindingRefreshAdmission`
  immediately before an actual provider operation: one actual refresh is
  exactly one charge; already-advanced and joined refreshes are free; a
  refresh never resets `REMOTE_ATTEMPT` and never creates a new chain.
- **`sponge-recovery-v2`** declares `REMOTE_ATTEMPT = 4` and
  `DELIVERY_BINDING_REFRESH = 1` (a safety bound, not an optimum). Later
  semantics change only through a new `policyId`.
- **Policy precedence:** session closing -> no demand -> exhausted
  `REMOTE_ATTEMPT` -> classification-specific provider action.
- **Bare 403 rule:** HTTP status alone never refines to
  `DELIVERY_BINDING_STALE`. Only an explicit provider-neutral
  `ProviderSignal.BINDING_STALE_CONFIRMED` does, and the raw status is still
  retained in the observation.
- **Retry-After is normalized.** `RetryAfterObservation` is `ABSENT`,
  `DELAY_SECONDS`, `HTTP_DATE` or `MALFORMED`, produced by a strict API-23
  parser for IMF-fixdate, RFC 850 and asctime forms. 429 with a valid
  `Retry-After` decides `WAIT_UNTIL_PROVIDER` and executes `WAIT_PROVIDER` (no
  budget spent, distinct from `SCHEDULE_BACKOFF`); absent or malformed fails
  closed until #50 defines a fallback.
- **Clock rule.** HTTP-date waits are computed only in `PROVIDER_WALL_CLOCK`
  (`max(0, notBefore - ProviderWallClock.now)`), never against Android elapsed
  realtime.
- **Incompatible refreshed material fails closed.** `RERESOLVE_PROVIDER` stays
  unimplemented (no consumer yet).
- **Shutdown order:** stop chains -> cancel provider waits -> cancel/settle
  refresh operations -> RecoveryCoordinator complete -> FetchBroker shutdown.

No YouTube wire details are decided here.

## Consequences

Positive:
- stable identity and durable storage are untouched; refreshed delivery
  material is execution state only;
- refresh is attributable, single-flight and budgeted: one actual operation is
  exactly one charge, and simultaneous stale failures share one operation;
- `Retry-After` has one normalized shape and one clock rule, so a provider
  wait can never consume `REMOTE_ATTEMPT` or mix clock domains;
- the chain survives a refresh with the same `FetchKey`, `ExtentSpec` and
  ledger, so the next owner uses the new revision without re-decision.

Negative:
- one more internal runtime component with its own cancellation barrier and a
  lock order that must be reviewed whenever the coordinator changes;
- a refresh that returns incompatible or failed material ends the chain as a
  terminal failure; there is no partial-attempt resume after a rebind;
- `Retry-After` absent or malformed is fail-closed, because no fallback is
  defined before #50.

Operational/recovery implications:
- the failure row is emitted before a provider wait and after a refresh
  returns, so evidence always carries the executed action;
- a no-demand or session-closing chain never starts a provider action;
- after shutdown the coordinator returns the last snapshot but starts nothing.

## Verification

- Host tests: `DeliveryBindingCoordinator` (compare-and-set, single-flight,
  joiner/initiator, cancellation reference-count, shutdown), `RetryAfter` /
  `HttpDate` parser, `FailureClassifier` refinement and `RecoveryCoordinator`
  v2 actions, plus negative tests for the falsification list in M2.md
  section 20 (M2-D).
- Provider oracle: `scripts/measurement/m2_provider_oracle.py` re-derives every
  classification, decision, `Retry-After` wait, refresh result, charge and
  revision transition from exported evidence and Media Lab provider traces
  (`scripts/ci/verify-m2-d-provider-evidence.sh host`); the falsification suite
  is `scripts/measurement/tests/test_m2_provider_oracle.py`.
- Android API 36: Media Lab N8/N9/N10 canonical scenarios against the
  deterministic provider simulator (`... device`); Android Compatibility
  API 23/34 runs the compatibility subset, including the API 23
  Retry-After/HTTP-date parser path.
- M2-ACC-07 and the new M2-ACC-08 are runtime-executable once the M2-D
  test/evidence PR lands.

## Supersession

None. This ADR extends ADR-0003, which remains Accepted. The ADR-0003 statement
"provider actions fail closed until M2-D" is superseded for
`WAIT_UNTIL_PROVIDER` and `REFRESH_DELIVERY_BINDING` only;
`RERESOLVE_PROVIDER` still fails closed.

## Canonical-doc impact

- `.work/milestones/M2.md`: M2-D section, PROVISIONAL items, slice/artifact
  table and M2-ACC-08.
- `.work/VERIFICATION.md`: M2-D artifact ownership, oracle invariants and the
  M2-ACC-07/08 executable path.
- `.work/ARCHITECTURE.md`: section 9.4/9.5 delivery-binding flow and
  `sponge-recovery-v2` budget.
- `.work/ROADMAP.md`: M2 status and the M2-D bullet.
