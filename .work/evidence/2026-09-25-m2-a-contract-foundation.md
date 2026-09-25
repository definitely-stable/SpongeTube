# Evidence: M2-A contract and evidence foundation

Date: **2026-09-25**

Status: **Contract foundation established — no runtime claim**
Issue: #75 (parent #74)
PR: #76 — `test(network): establish M2 resilience contracts`

## Question

Can the M2 resilience semantics, evidence identity and ownership boundaries be
frozen as a falsifiable contract? The goal is that M2-B (route), M2-C (recovery
chain/budget) and M2-D (delivery binding) can start without re-deciding
fundamental semantics, and without changing any M1 identity/storage/fetch
invariant.

## Hypothesis

1. Every FROZEN M2 decision can be expressed as a host-checkable rule with at
   least one negative test that fails on a materially wrong input.
2. Two cross-slice schemas (`m2-scenario-v1`, `m2-run-manifest-v1`) are enough
   for M2-A; subsystem schemas can wait for their owning producers.
3. None of this requires production code, a new dependency or a change to
   historical M0/M1 contracts.

## Build

- Contract commit (PR head verified): `0921bfa87f9fa81599df498a6502047eba9df676`
- Base: `main` at `b26b397` (M1 canonical acceptance evidence, #73)
- Verify workflow run: `36095249234`
  - job `verify` (ubuntu-24.04): success
  - job `windows verify` (windows-2025): success
- Also green on the same head: `android-smoke` (run `36095249159`) and
  `m1-recovery` (run `36095249370`). M1 `acceptance` was skipped, as it
  normally is on PRs.

In the `verify` job log:

- the host test step ran 183 tests with no failures, including all 65
  `test_m2_contracts` tests;
- `check assembleDebug` succeeded;
- the second Gradle invocation reused the configuration cache.

## Artifacts introduced

| Artifact | Kind |
| --- | --- |
| `.work/milestones/M2.md` | normative M2 contract |
| `.work/schemas/m2-scenario-v1.schema.json` | cross-slice resolved scenario identity |
| `.work/schemas/m2-run-manifest-v1.schema.json` | cross-slice run identity |
| `.work/schemas/examples/m2/*.example.json` | N5 stochastic scenario, N7 route scenario, bound run manifest |
| `scripts/measurement/m2_contracts.py` | host reference oracle for FROZEN contracts |
| `scripts/measurement/tests/test_m2_contracts.py` | 65 contract/falsification tests |

## Falsification coverage

The suite fails on each item of `.work/milestones/M2.md` §18.3:

1. N5 stochastic scenario without seed;
2. seed change not changing scenario hash;
3. one fault claiming two primary planes (in two plane lists, or mislabelled);
4. N7-like route proof on `ADB_REVERSE`/`HOST_ONLY` media path;
5. `UNKNOWN` validated interpreted as TRUE (pending and observed phases);
6. VPN → non-VPN auto-allowing external fetch (also VPN → pending capabilities);
7. route/VPN/rebind transition mutating or removing persisted extent identity;
8. bare 403 classified as stale binding or auto-triggering binding refresh;
9. 429 classified as transport/other, or observed on the TRANSPORT plane;
10–13. recovery-ledger reset after `SHARED_FETCH_REPLACED`, `MEDIA3_REOPEN`,
   `ROUTE_EPOCH_CHANGED`, `DELIVERY_REBOUND` (also `TRANSPORT_RECONNECT`);
14. rebinding that changes immutable `ExtentSpec`/work identity;
15. ENOSPC classified as provider rejection or transport;
16. SUBTRACT/ORDER across Android, Media Lab, fault-host and provider clocks;
17. evidence retaining signed URLs, `Authorization`, cookies, PO/visitor tokens,
    SSID/BSSID, raw IPv4/IPv6 or URL credentials;
18. pre-M2 schema bytes changed (SHA-256 pinned for all 23 M0/M1 schemas), and
    an M1 run manifest accepted as an M2 manifest.

As a separate local check, six key rules were disabled one at a time: layer
transition reset, VPN→non-VPN, UNKNOWN-as-TRUE, seed requirement,
cross-clock comparison and rebinding identity. The suite failed for each
mutation, so these negatives are not vacuous.

## Decisions

FROZEN (M2.md §3.1, F-01..F-14):

- persisted identity is independent of route/URL/session;
- route change never invalidates valid coverage;
- no silent VPN bypass;
- single remote owner;
- observation ≠ classification ≠ decision ≠ action;
- recovery is bounded, with no implicit budget reset across layers;
- mutable binding is separate from immutable work;
- one primary plane per fault;
- a persisted seed is part of scenario identity;
- no cross-domain clock comparison;
- no secrets in evidence;
- a live provider is not an oracle;
- bare 403 is not stale-binding evidence, and 429 is rate limiting.

PROVISIONAL (M2.md §3.2), each with an owning slice:

- route field set (M2-B);
- classification enum, budget shape, action costs and limits (M2-C);
- `DeliveryBindingRevision` representation and `Retry-After` artifact (M2-D);
- fault injectors and N2/N3/N5 parameters (M2-E);
- transport backend(s) (M2-G).

DEFERRED (M2.md §3.3):

- YouTube/SABR, PO-token, client profiles, JS/challenge runtime;
- expiry policy;
- persistent unfinished-attempt resume;
- transport winner;
- Smart Buffer tuning;
- quota/eviction policy;
- physical thresholds.

## Dependency boundaries

- **#50** (M3-A provider feasibility): review dependency for M2-D and the
  production provider seam. It does not block M2-A/B/C. Its results may change
  the provider/delivery implementation without breaking this contract.
- **#23** (dependency/tooling): no dependency is added by M2-A. Toxiproxy and
  `tc/netem` stay candidates until M2-E.

## Unchanged

FetchBroker, PlaybackBridge and Media3 retry runtime; Android connectivity
code; transport; provider resolver; CI workflows; all M0/M1 schemas and
examples.

## Known limitations

M2-A establishes contract and evidence semantics only.

It does not prove Android route recovery, provider compatibility, transport
superiority, or representative physical-device performance.

`./gradlew check assembleDebug` was not run in the authoring container because
it has no Android SDK. The CI Verify run above is the build/configuration-cache
evidence. `m2_contracts.py` is a reference oracle for contract semantics, not a
verifier of runtime artifacts. M2-ACC-01..07 become executable for real runs
only when their owning slices land producers and verifiers.
