# Evidence: M2-D delivery binding refresh and deterministic provider fault recovery

Date: **2026-09-26**

Status: **Complete — final-head CI verification green**

## Question

Is the delivery binding local mutable execution state that
`DeliveryBindingCoordinator` replaces under `RecoveryCoordinator` ownership, so
that one RecoveryChain keeps its immutable `FetchKey`/`ExtentSpec` identity and
its ledger while the current revision advances `binding-1` -> `binding-2` with
exactly one `DELIVERY_BINDING_REFRESH` charge per actual provider operation, and
does a normalized `Retry-After` / `BINDING_STALE_CONFIRMED` observation produce
deterministic provider recovery that an independent oracle re-derives
(M2-ACC-07, M2-ACC-08)?

## Hypothesis

- Every new FetchBroker owner selects the current delivery-binding snapshot;
  joining consumers never change an in-flight owner's binding; a revision
  changes only by one successful refresh and is never derived from material or
  a URL.
- One actual provider refresh operation is exactly one
  `DELIVERY_BINDING_REFRESH` charge; `JOINED_REFRESH`, `ALREADY_ADVANCED`,
  `NOT_ADMITTED`, `CLOSED` and provider waits charge nothing; a refresh never
  resets `REMOTE_ATTEMPT` and never creates a new RecoveryChain.
- A bare 403 never refines to a stale binding; 429 stays a provider-plane
  observation; a valid `Retry-After` decides `WAIT_UNTIL_PROVIDER` and no
  request or broker owner exists before the wait completes; an absent or
  malformed `Retry-After` fails closed.
- An incompatible or failed refresh fails closed with no further physical
  request, and no persisted extent is ever removed.
- An independent oracle re-derives every classification, decision,
  `Retry-After` wait, refresh result, budget charge, revision transition and
  provider fault attribution from its own tables (a live provider is never the
  oracle, F-13).

## Build

- PRs: #82 (contract), #83 (runtime), #84 (evidence and CI)
- Verified implementation head: `365d74eb9cf021626c93064bea2f90a558e117e1`
- Branch: `test/81-m2-d-provider-recovery`
- `main` base: `63dd3a8b798f77476deb921988e651b247d0160f` (M2-D runtime, #83)
- Decision record: `.work/adr/0004-separate-immutable-work-from-delivery-binding.md`
- Policy: `sponge-recovery-v2`, `REMOTE_ATTEMPT = 4`,
  `DELIVERY_BINDING_REFRESH = 1`, backoff
  `window = min(5000 ms, 500 ms * 2^(n-1))`, `delay = uniform(0, window)`
  (full jitter, base/cap 500/5000), no delay before the initial request
- Host test policy: `sponge-recovery-test-v2`, same limits, backoff windows
  100/200/400 ms, deterministic full-window jitter, recording sleeper that
  advances the harness monotonic clock

## Environment

- Host: Windows 11, JDK 17 Temurin (`17.0.19+10`), Python 3.13.15, Gradle
  wrapper 9.6.0; host tests run on the JVM with a fake monotonic clock and no
  network.
- No local Android emulator (no AVD, no attached device); the authoritative
  Android/Media3 proof is the final-head repository CI listed below.
- On Windows the shell verifiers need a `python3` executable in PATH (an
  `exec python "$@"` shim is sufficient) and `JAVA_HOME` pointing at JDK 17;
  repository CI provides both directly.

## Fixture

- Host: scripted provider observations over `FetchKey`s under `fixture:M2C/...`
  (`ProviderRecoveryEvidenceHostTest`, `RecoveryHarness`); each case seeds one
  persisted extent and asserts it is not removed.
- Android: F1 `segment-1-00001.m4s` (81 811 bytes, SHA-256
  `08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943`) served by
  the Media Lab provider simulator.

## Network profile

- Host: no network; deterministic scripted `HttpResponse` observations and a
  recording sleeper instead of real transport.
- Android: Media Lab provider variants N8 `HTTP_403_BARE`,
  N9 `HTTP_429_RETRY_AFTER_DELAY_SECONDS`, N9 `HTTP_429_RETRY_AFTER_HTTP_DATE`
  and N10 `BINDING_EXPIRY_REFRESH` (device canonical scenarios); Android
  Compatibility (API 23/34) runs the N9 `HTTP_429_RETRY_AFTER_HTTP_DATE`
  subset. The simulator reports a fixed virtual provider wall clock
  `1790337600000` = 2026-09-25T12:00:00Z in `/__lab/config` and serves
  `provider-fault-events-v1` at `GET /__lab/provider/events`; the lab-only
  header `X-Sponge-Provider-Binding: STALE` maps to
  `BINDING_STALE_CONFIRMED`. Deterministic simulator semantics, never YouTube
  semantics; no VPN or route switch.

## Baseline

M2-C at `d07b10e` (`sponge-recovery-v1`, `REMOTE_ATTEMPT = 4`, 500/5000
backoff): `WAIT_UNTIL_PROVIDER` and `REFRESH_DELIVERY_BINDING` were decided but
executed as `FAIL_CLOSED_ACTION_UNAVAILABLE`. There was no delivery-binding
representation, no `Retry-After` execution, and no provider-neutral signal
that could distinguish a stale binding from a generic provider rejection.

## Procedure

- Engine unit tests: `./gradlew --configuration-cache :core:engine:testDebugUnitTest`
  (test counts read from `core/engine/build/test-results/testDebugUnitTest/*.xml`).
- M2-C regression: `bash scripts/ci/verify-m2-c-recovery-evidence.sh host build/m2-c-recovery`
  (re-runs `RecoveryEvidenceHostTest` and verifies every case with the
  independent recovery oracle).
- M2-D host evidence: `bash scripts/ci/verify-m2-d-provider-evidence.sh host build/m2-d-provider`
  (re-runs `ProviderRecoveryEvidenceHostTest`, then verifies every case with
  `scripts/measurement/m2_provider_oracle.py`).
- Python measurement suite: `python -m unittest discover -s scripts/measurement/tests -p "test_*.py"`.
- Media Lab tests: `./gradlew --configuration-cache :tools:media-lab:test`.
- CI: `Verify` (incl. `verify-m2-d-provider-evidence.sh host`), `Windows verify`,
  `Android Smoke` API 36 (`ProviderRecoveryAndroidTest` for the canonical
  scenarios and `... device` verification), `Android Compatibility` API 23/34,
  and `M1 Recovery`.

## Results

### Local

| Check | Result |
| --- | --- |
| `:core:engine:testDebugUnitTest` | PASS on final-head Verify; targeted review regressions for zero-charge shared refresh paths and fatal refresh errors are included |
| M2-C host cases under `sponge-recovery-test-v2` | 7 / 7 cases PASS; M2-ACC-05 in 7, M2-ACC-06 in 5 |
| M2-D host provider cases | 14 / 14 cases PASS; M2-ACC-05 in 14, M2-ACC-06 in 7, M2-ACC-07 in 5, M2-ACC-08 in 10 |
| `:tools:media-lab:test` (provider simulator) | 86 tests, 0 failures, 0 errors, 1 skipped (Windows symlink assumption) |
| Python measurement suite | `Ran 377 tests`; OK, 2 skipped, including the M2-D admission-exhaustion falsification added during final review |
| Python suite, pre-existing Windows-only errors | 22 `test_m1_evidence_kernel` teardown errors (`PermissionError: [WinError 32]` on the temp SQLite file); they do not occur on Linux CI |
| Historical schema bytes | unchanged; M2-D adds only new versioned schema files |

Host provider cases (production coordinator, independent oracle). Chains,
attempt/charge counts, refresh ops/charges, revisions and gates come from each
case's `provider-verification-summary.json`; waits and terminals are the
verified `case.json` / failure-row values the summary reports on.

| Case | Chains | Physical attempts = charges | Refresh ops = refresh charges | Provider waits (ms) | Revisions | Terminal | M2-ACC-05 | M2-ACC-06 | M2-ACC-07 | M2-ACC-08 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `n8-bare-403` | 1 | 1 = 1 | 0 = 0 | — | `binding-1` | TERMINAL_FAILURE | PASS | NOT_EXERCISED | NOT_EXERCISED | PASS |
| `n9-delay-seconds` | 1 | 2 = 2 | 0 = 0 | 2000 | `binding-1`, `binding-1` | SUCCESS | PASS | PASS | NOT_EXERCISED | PASS |
| `n9-http-date` | 1 | 2 = 2 | 0 = 0 | 2000 | `binding-1`, `binding-1` | SUCCESS | PASS | PASS | NOT_EXERCISED | PASS |
| `n9-retry-after-absent` | 1 | 1 = 1 | 0 = 0 | — | `binding-1` | TERMINAL_FAILURE | PASS | NOT_EXERCISED | NOT_EXERCISED | PASS |
| `n9-retry-after-malformed` | 1 | 1 = 1 | 0 = 0 | — | `binding-1` | TERMINAL_FAILURE | PASS | NOT_EXERCISED | NOT_EXERCISED | PASS |
| `n10-binding-expired-refresh` | 1 | 2 = 2 | 1 = 1 | — | `binding-1`, `binding-2` | SUCCESS | PASS | PASS | PASS | PASS |
| `n10-refresh-incompatible` | 1 | 1 = 1 | 1 = 1 | — | `binding-1` | TERMINAL_FAILURE | PASS | NOT_EXERCISED | PASS | PASS |
| `n10-refresh-failed` | 1 | 1 = 1 | 1 = 1 | — | `binding-1` | TERMINAL_FAILURE | PASS | NOT_EXERCISED | NOT_EXERCISED | PASS |
| `n10-already-advanced` | 2 | 4 = 4 | 1 = 1 | — | `binding-1`, `binding-1`, `binding-2`, `binding-2` | SUCCESS ×2 | PASS | PASS | PASS | PASS |
| `n10-concurrent-single-flight` | 2 | 4 = 4 | 1 = 1 | — | `binding-1`, `binding-1`, `binding-2`, `binding-2` | SUCCESS ×2 | PASS | PASS | PASS | PASS |
| `refresh-budget-exhausted` | 1 | 2 = 2 | 1 = 1 | — | `binding-1`, `binding-2` | BUDGET_EXHAUSTED | PASS | PASS | PASS | NOT_EXERCISED |
| `provider-wait-cancelled` | 1 | 1 = 1 | 0 = 0 | 30000 | `binding-1` | NO_REMAINING_DEMAND | PASS | NOT_EXERCISED | NOT_EXERCISED | NOT_EXERCISED |
| `shutdown-during-refresh` | 1 | 1 = 1 | 1 = 1 | — | `binding-1` | SESSION_TERMINATION | PASS | NOT_EXERCISED | NOT_EXERCISED | NOT_EXERCISED |
| `rate-limit-budget-exhausted` | 1 | 4 = 4 | 0 = 0 | 0, 0, 0 | `binding-1` ×4 | BUDGET_EXHAUSTED | PASS | PASS | NOT_EXERCISED | NOT_EXERCISED |

Full failure path (`n10-binding-expired-refresh`): one chain `recovery-1`;
owners `fetch-1` (403 with the explicit stale signal on `binding-1`) and
`fetch-2` (206 on `binding-2`); REMOTE_ATTEMPT `0→1`, `1→2`; failure
`recovery-1:failure-1` is `DELIVERY_BINDING_STALE` -> `REFRESH_DELIVERY_BINDING`
/ `STALE_BINDING_SIGNAL` -> `REFRESHED` with the `DELIVERY_BINDING_REFRESH`
charge `0→1` (limit 1); the delivery events show `binding-1` ->
`REFRESH_REQUESTED` -> `REFRESH_STARTED refresh-1` -> `REFRESH_SUCCEEDED`
`binding-1` -> `binding-2` -> selection `binding-2`; terminal SUCCESS.

Classification cases proven (host): bare 403 -> `PROVIDER_REJECTED` ->
`FAIL_TERMINAL` with zero refresh requests; 429 + valid `Retry-After: 2` ->
`PROVIDER_RATE_LIMITED` -> `WAIT_UNTIL_PROVIDER` -> `WAIT_PROVIDER` of 2000 ms
with no new physical request before the wait elapsed; 429 + HTTP-date ->
the same wait in `PROVIDER_WALL_CLOCK`; 429 without or with a malformed
`Retry-After` -> `FAIL_TERMINAL` / `RETRY_AFTER_ABSENT` / `RETRY_AFTER_MALFORMED`;
403 + explicit stale signal -> `DELIVERY_BINDING_STALE` ->
`REFRESH_DELIVERY_BINDING` with one charged actual refresh (`REFRESHED`,
`JOINED_REFRESH`, `ALREADY_ADVANCED` continue the chain) or fail-closed
`INCOMPATIBLE` / `FAILED`; a second stale owner after the single refresh reaches
refresh admission, returns zero-charge `NOT_ADMITTED` with
`DELIVERY_BINDING_REFRESH` exhausted, and terminates `BUDGET_EXHAUSTED`; shutdown during a
refresh -> `SESSION_TERMINATION`; a cancelled provider wait ->
`NO_REMAINING_DEMAND` without advancing the clock.

### Canonical Android scenarios

| Scenario | Expected physical requests | Refresh ops | Wait | Terminal | Result |
| --- | --- | --- | --- | --- | --- |
| `N8_BARE_403` | 1 | 0 | — | TERMINAL_FAILURE | PASS: attempts=1 refreshOps=0; ACC-05 PASS, ACC-08 PASS |
| `N9_429_DELAY_SECONDS` | 2 | 0 | 2000 ms | SUCCESS | PASS: attempts=2 refreshOps=0; ACC-05/06/08 PASS |
| `N9_429_HTTP_DATE` | 2 | 0 | 2000 ms (PROVIDER_WALL_CLOCK) | SUCCESS | PASS: attempts=2 refreshOps=0; ACC-05/06/08 PASS |
| `N10_BINDING_EXPIRED_REFRESH` | 2 media + 1 refresh | 1 | — | SUCCESS | PASS: attempts=2 refreshOps=1; ACC-05/06/07/08 PASS |

### CI

| Check | Run | Result |
| --- | --- | --- |
| Verify (incl. `verify-m2-c-recovery-evidence.sh host` and `verify-m2-d-provider-evidence.sh host`) | 36247598901 | PASS |
| Windows verify | 36247598901 | PASS |
| Android Smoke API 36 (M2-C `RecoveryOriginAndroidTest` under v2, four canonical provider scenarios + `... device`) | 36247598891 | PASS |
| Android Compatibility API 23 / 34 (`RetryAfterAndroidTest` in the full suite; `N9_429_HTTP_DATE`: exported evidence on API 34, instrumentation + provider-side proof on API 23) | 36247598826 | PASS |
| M1 Recovery | 36247598892 | PASS |

### #50 conclusions used

None. #50 has no provider findings at 2026-09-26, so no provider-specific
conclusion is used; each observed fact keeps the conservative interpretation
recorded in `.work/milestones/M2.md` section 20 (M2-D).

### Unresolved #50 conclusions

- what the binding is for the selected path;
- binding scope (asset/representation/resource/session);
- explicit expiry;
- a stale-binding signal besides the HTTP status;
- bare HTTP 403 in the selected path;
- whether a refresh can change the URL set without changing media identity;
- a higher-level re-resolve;
- stable range continuation after a refresh;
- 429 throttling scope;
- client profiles, PO token, SABR and JS challenge (out of scope).

### Retry-After parser contract summary

`RetryAfterObservation(rawKind, delaySeconds?, notBeforeUtcEpochMs?)` with
`rawKind` `ABSENT` | `DELAY_SECONDS` | `HTTP_DATE` | `MALFORMED`; the raw header
string is never retained. The Kotlin parser is API-23 (no `java.time`,
`Calendar` or `SimpleDateFormat`) and
`scripts/measurement/m2_contracts.parse_retry_after` implements the same rules
as the contract oracle: `^[0-9]+$` delay-seconds with an overflow check, where a
value above the maximum delay is `MALFORMED` and never clamped; HTTP-date only
in the three GMT forms (IMF-fixdate, RFC 850 with the two-digit-year rule,
asctime) under a case-sensitive grammar with month, day, hour, minute and
second validated and the weekday matched to the date; everything else is
`MALFORMED`. Wait computation: `DELAY_SECONDS` waits `delaySeconds * 1000`;
`HTTP_DATE` waits `max(0, notBeforeUtcEpochMs - ProviderWallClock.now)` in
`PROVIDER_WALL_CLOCK` only, never against Android elapsed realtime.

### Binding revision sequence

The coordinator starts at `binding-1`; one successful refresh installs the next
revision and revisions are never reused or derived from material. N10
`BINDING_EXPIRED_REFRESH` observes `binding-1` on the stale owner and
`binding-2` on the next owner of the same `FetchKey`/`ExtentSpec`;
`n10-already-advanced` and `n10-concurrent-single-flight` show `binding-1` ->
`binding-2` with exactly one refresh charge.

### Exact physical request counts

- `N8_BARE_403`: **1** physical request, no refresh.
- `N9_429_DELAY_SECONDS`: **2** physical requests and one provider wait of
  **2000 ms**.
- `N9_429_HTTP_DATE`: **2** physical requests and one provider wait of
  **2000 ms**.
- `N10_BINDING_EXPIRED_REFRESH`: **2 media requests + 1 refresh**.

### Defects found during verification

- **API-24 math in the date parser.** The first parser used `Math.floorDiv` /
  `Math.floorMod` (Java 8, Android API 24). It was replaced with Kotlin integer
  math before any device run; `RetryAfterAndroidTest` now proves the parser on
  API 23.
- **Binding selection dropped during shutdown.** `recordSelection` was a no-op
  after the binding coordinator closed, so an attempt admitted concurrently
  with session shutdown could lose its `BINDING_SELECTED_FOR_ATTEMPT` row.
  Selections are now always recorded.
- **Stop overriding a real refresh result.** An intermediate coordinator
  version recorded any refresh result that arrived after the chain was asked
  to stop as `ABANDONED`, even a successful refresh — the failure row could
  contradict the delivery-binding evidence. A delivered result is now recorded
  as it is; only an operation that was actually cancelled
  (`Failed(cancelled = true)`: shutdown or every waiter left) is `ABANDONED`.
- **Oracle rejecting a legitimate terminal refresh charge.** The recovery
  oracle treated the single `DELIVERY_BINDING_REFRESH` charge of an
  INCOMPATIBLE/FAILED refresh as a request after a terminal decision; it now
  excludes exactly that charge (same failureId) and a regression test covers
  both results. The provider oracle's temporary workaround was removed.
- **Origin wait bound.** The provider-wait spacing on the Media Lab trace is
  measured from the throttled request's handler start (a sound lower bound in
  `HOST_MEDIA_LAB_MONOTONIC`) instead of its completion timestamp.
- **API 23 additional test output.** AGP cannot pull additional test output
  from the API 23 image (`File name too long`); like M2-B, API 23 is
  instrumentation-only for evidence export, with an independent provider-side
  check of the origin trace and provider fault events.
- **Refresh budget was enforced too early.** Final review found that checking
  `DELIVERY_BINDING_REFRESH` before entering `DeliveryBindingCoordinator`
  incorrectly blocked zero-charge `ALREADY_ADVANCED` and `JOINED_REFRESH`
  paths. The budget is now enforced only at actual refresh admission; a second
  physical refresh at limit 1 returns zero-charge `NOT_ADMITTED` and
  terminates `BUDGET_EXHAUSTED`. Targeted runtime and independent-oracle
  falsification tests cover all three paths.
- **Fatal refresher errors were normalized.** A fatal `Error` from
  `DeliveryBindingRefresher` could previously settle shared waiters as an
  ordinary provider failure. The coordinator now clears single-flight
  ownership, completes waiters exceptionally and rethrows the fatal error;
  the regression test accounts for coroutine stack-trace recovery semantics.
- **Oracle action count after admission hardening.** The first clean stacked
  rebase exposed a stale positive-test assertion that expected one
  `REFRESH_DELIVERY_BINDING` action in the exhausted case. There are
  correctly two logical refresh actions: `REFRESHED` followed by
  `NOT_ADMITTED`. The assertion was corrected; final-head Python suite is
  green.

## Gates

- **M2-ACC-05 — Failure Separation:** PASS in all 14 host cases and in all
  four canonical Android scenarios (API 36) and `N9_429_HTTP_DATE` on API 34.
- **M2-ACC-06 — Bounded Recovery Lineage:** PASS in 7 host cases and in the
  three multi-owner Android scenarios (N9 delay, N9 date, N10); charge =
  physical attempt = origin media request.
- **M2-ACC-07 — Mutable Binding Independence:** PASS in 5 host cases and on
  Android `N10_BINDING_EXPIRED_REFRESH` (`binding-1` -> `binding-2`, same chain,
  FetchKey and extent, one refresh charge, one provider refresh request).
- **M2-ACC-08 — Deterministic Provider Recovery:** PASS in 10 host cases and in
  all four canonical Android scenarios, each verified against the Media Lab
  origin trace and `provider-fault-events-v1`.

## Schemas and verifier

- `.work/schemas/failure-decision-events-v2.schema.json`
- `.work/schemas/delivery-binding-events-v1.schema.json`
- `.work/schemas/provider-fault-events-v1.schema.json`
- `.work/schemas/provider-verification-summary-v1.schema.json`
- unchanged and byte-stable: `.work/schemas/failure-decision-events-v1.schema.json`,
  `recovery-budget-events-v1.schema.json`, `fetch-events-v4.schema.json`,
  `bridge-events-v2.schema.json`
- examples: `.work/schemas/examples/m2/failure-decision-v2.example.json`,
  `delivery-binding-events-v1.example.json`,
  `provider-fault-events-v1.example.json`,
  `provider-verification-summary-v1.example.json`
- verifier: `scripts/measurement/m2_provider_oracle.py` (no production import)
  with lineage re-derivation in `scripts/measurement/m2_recovery_oracle.py` v2;
  falsification suites `scripts/measurement/tests/test_m2_provider_oracle.py`
  and `scripts/measurement/tests/test_m2_recovery_oracle.py`
- CI: `scripts/ci/verify-m2-d-provider-evidence.sh host|device`

## M1 / M2-C regression reasoning

- M1 Recovery: `M1RecoveryProvider` records `recoveryPolicyId = sponge-recovery-v2`,
  `recoveryRemoteAttemptLimit = 4`, `maxAttemptsPerOwner = 1` and
  `media3MaxRetries = 0`; the M1 verifier bounds owners per work item by the
  declared chain limit while the degenerate per-owner/Media3 bounds stay.
  M1 invariants (single remote owner, bounded requests, no hidden Media3
  upstream, publication and cancellation barriers, recovery after restore) are
  unchanged.
- M2-C host cases still pass under `sponge-recovery-test-v2`: all seven cases
  PASS, with M2-ACC-05 in 7 and M2-ACC-06 in 5.
- Historical schemas are byte-stable; `fetch-events-v4` and `bridge-events-v2`
  are unchanged, `failure-decision-events-v1` stays for historical artifacts,
  and the oracle keeps the v1 policy tables for historical documents.

## Limitations

M2-D proves provider-neutral delivery-binding replacement, Retry-After handling
and deterministic provider recovery.

It does not by itself prove that a live YouTube HTTP 403 means an expired
binding, does not select a production YouTube client/profile, does not
implement SABR/PO-token/challenge handling, and does not select the production
transport.

Additional limitations of this record:

- #50 has no provider findings at 2026-09-26; every observed fact keeps the
  conservative interpretation of `.work/milestones/M2.md` section 20 (M2-D);
- the lab stale signal (`X-Sponge-Provider-Binding: STALE`) is simulator-only;
  the production stale-binding signal remains unresolved;
- no live provider is involved (a live provider is never a deterministic
  acceptance oracle, F-13);
- HTTP-date waits use the simulator's fixed virtual provider clock; clock skew
  and real provider dates are not exercised;
- the local environment had no Android emulator; the Android/Media3 proof is
  the final-head CI listed above;
- on API 23 the client evidence is asserted on-device but not exported (AGP
  legacy test-output path); the exported, oracle-verified device evidence is
  API 36 and API 34.

## Conclusion

The hypothesis holds on independent host evidence and repository Android
evidence: one delivery-binding owner under RecoveryCoordinator ownership, one
charge per actual refresh, free joins/advances, the same chain ledger and
immutable identity across the `binding-1` -> `binding-2` transition, a bare 403
that never refreshes, `Retry-After` waits without requests, and deterministic
N8/N9/N10 provider recovery re-derived by an independent oracle.
M2-ACC-05/06/07/08 pass; M2-D is complete. The production stale-binding signal
and every other #50-dependent fact remain unresolved and fail closed.
