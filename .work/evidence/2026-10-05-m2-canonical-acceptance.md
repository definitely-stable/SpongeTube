# Evidence: M2 canonical acceptance

Date: **2026-10-05**

Status: **PASS — 10/10 normative M2 acceptance gates**
Parent milestone: #74
Canonical acceptance infrastructure: #128 / PR #129
Post-merge hardening: PR #131, PR #134, PR #137, PR #138

## Question

Does the merged M2 Network & Provider Resilience milestone satisfy every
normative M2-ACC gate on one exact `main` revision, using fresh same-source
owning-slice evidence and independent verification, without selecting a
transport from emulator-only evidence or promoting live-provider assumptions
into acceptance?

## Build

- Commit: `4f4e2c6ed1ee94bcfc7b9a41a9edb4b4f0c73929`
- Merge: PR #138, `fix(ci): serialize Android smoke TestStorage producers`
- Workflow: `M2 Canonical Acceptance`
- Workflow run: `37350866894`
- Successful attempt: **2**
- Canonical device: Android API 36 emulator
- GitHub-hosted runner only; no self-hosted runner
- G3 decision: `TECHNICALLY_ELIGIBLE_NO_SELECTION`
- Selected backend: `null`
- Physical-device performance evidence: `false`
- Performance selection allowed: `false`
- Canonical N5 effect: `INCONCLUSIVE_STOCHASTIC_EFFECT`

## Rerun provenance

Attempt 1 failed before the F3 VPN test began. During
`Prepare API 36 emulator`, `sdkmanager` downloaded a corrupt
`x86_64-36_r07.zip` and reported:

`Error on ZipFile unknown archive`

The failed F3 artifact was only 207 bytes and contained no VPN acceptance
evidence. Every other completed owning path on the exact same commit was
already green.

Only the failed F3 job was rerun. Attempt 2 kept the exact same source commit,
successfully prepared the API 36 emulator, executed the real N7 VPN continuity
and explicit-direct-override test, and passed. The final H aggregate then
completed successfully. No repository change was made between attempts.

## Canonical procedure

The H workflow re-executed the accepted M2 owning paths from the same exact
source revision:

1. Android Smoke for M2-B/C/D retained route, recovery and provider evidence;
2. M2-E transport fault evidence;
3. M2-E network fault evidence;
4. M2-F2 default-route recovery;
5. M2-F3 VPN continuity/privacy;
6. a fresh six-scenario M2-G2 transport evaluation;
7. M2-G3 retained transport decision;
8. the independent H collector/verifier.

The H verifier required exactly ten unique PASS gates, rejected mixed source
revisions, revalidated owning summary schemas and privacy constraints, bound
every retained proof by path/SHA-256/size, and independently checked G3 against
its fresh G2 source.

## Results

```text
M2 canonical acceptance PASS: 10/10 gates
M2 acceptance index verified
```

| Gate | Result | Canonical proof |
| --- | --- | --- |
| M2-ACC-01 | PASS | resolved fault scenario identity and retained stochastic seed |
| M2-ACC-02 | PASS | independent fault-plane attribution across transport/network harnesses |
| M2-ACC-03 | PASS | F3 VPN disappearance privacy policy; no unintended direct external fetch |
| M2-ACC-04 | PASS | persisted media identity survives route/binding changes |
| M2-ACC-05 | PASS | observation, classification and recovery action remain separated |
| M2-ACC-06 | PASS | one bounded RecoveryChain across owner/route/refresh boundaries |
| M2-ACC-07 | PASS | delivery binding refresh preserves stable work identity |
| M2-ACC-08 | PASS | deterministic N8/N9/N10 provider recovery |
| M2-ACC-09 | PASS | scoped transport/network fault harness fidelity and cleanup |
| M2-ACC-10 | PASS | paired transport evaluation integrity with no unsupported winner |

## Canonical artifacts

GitHub Actions workflow run: `37350866894`.

| Artifact | ID | Size (bytes) | SHA-256 |
| --- | ---: | ---: | --- |
| `m2-canonical-acceptance` | 11362674471 | 35,796 | `98f8701842fc37d5141f0565836e4e28fbdc8784a72474e802acfb1c9dfff725` |
| `android-smoke-evidence` | 11363510676 | 93,411,454 | `0cc4f30887030ddae44f76ef81e37865c4532ca1841834da2fbf6f36c8803508` |
| `m2-e-transport-api36` | 11362807779 | 105,354 | `74af45722ce1363634eef7a43ed6a8cd50f5bd151e4375a3748c3e7d8dd9b347` |
| `m2-e-network-api36` | 11362667960 | 101,327 | `70773373671a3541ffaa253f77e36dc7a5ae5e1429ca20ce68909018f5f1bc1e` |
| `m2-f2-route-recovery` | 11362641834 | 268,224 | `635997c1c702239f0dda6ae2536ff252ff995f665f13767dbe6449369133a4b8` |
| `m2-f3-vpn-continuity` | 11363517028 | 304,269 | `5926ca61bb3a02d6443a195a4e227aab39b3283e3fcf8b1c833c03ed418a3854` |
| `m2-g2-canonical-evidence` | 11362573827 | 33,256 | `6410fe993f71cc48016313436b7812695af788e0404c45bad2822c3878cd0802` |
| `m2-g3-transport-decision` | 11363675978 | 17,634 | `c574fe4576219d6276bb37d0f0926e8090c1e5e3fcfaae70aabe84fb00a0df0d` |

Fresh G2 scenario artifacts:

| Scenario artifact | ID | SHA-256 |
| --- | ---: | --- |
| `m2-g2-n0-api36-paired` | 11363185796 | `ede2cfceb0d02b74673c9298ddcec2c607dadd324fa806c8be01dc622b32d26c` |
| `m2-g2-n2-api36-paired` | 11362878465 | `ef9618c04976a08c69f75f76d268493c65adf527092216ee9a6ebe0ea936fe1a` |
| `m2-g2-n3-api36-paired` | 11363601493 | `81fa779f14116b1e2624aa4cca96cd7dec1e458f56ca77feb5d0120b523c216a` |
| `m2-g2-n5-api36-paired` | 11362937257 | `6726d55c73852203ded98a0328106e7e9c95573e6ea113fb44f096d77cf3a15f` |
| `m2-g2-n6-api36-paired` | 11363206062 | `2124de60f0b1bd15956b8c129a3a2eb6550a3491a339fd38b7fe4da323287c3e` |
| `m2-g2-route-api36-paired` | 11363236830 | `43c21f7a620e77f6f86925f7e642de6f0b990b5201086e7813d43dcd2499208d` |

## Hardening discovered during closure

The canonical closure process found and fixed four evidence-infrastructure
problems without weakening production semantics:

- Platform HttpEngine may normalize a causally proven downstream reset as
  `CONNECT_TIMEOUT`; the verifier accepts it only with retained upstream
  `CLIENT_DISCONNECTED + 0 bytes` reset proof.
- G2-E route replacement is Wi-Fi-only in the canonical emulator lab so
  CELLULAR fallback cannot manufacture extra route epochs/owners.
- Android Smoke serializes storage and engine TestStorage-producing
  instrumentation suites on the shared AVD.
- Media Lab provider integration tests wait for asynchronously persisted trace
  rows and ignore an incomplete trailing JSONL line.

The exact two-owner route invariant, VPN privacy policy, retry budgets,
production failure classifier, production route policy and production
transport choice were not weakened.

## Decision

**Accept M2 Network & Provider Resilience.**

All ten normative M2 acceptance gates pass on the exact merged main revision.
M2-H and parent M2 #74 may close.

## Limitations

- Evidence is deterministic API36 emulator correctness/resilience evidence, not
  representative physical-device performance.
- No transport backend is selected.
- N5 remains `INCONCLUSIVE_STOCHASTIC_EFFECT`.
- Live YouTube/provider compatibility is not an M2 acceptance claim.
- Production YouTube delivery feasibility remains the M3-A / #50 risk track.
- Dependency/licensing/distribution constraints remain governed by #52/#23.
