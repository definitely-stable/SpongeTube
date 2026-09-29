# SpongeTube M2-F — Canonical Route/Recovery Evidence Closure

Date: **2026-09-29**  
Status: **PASS — M2-F complete**  
Parent: **#93**  
F4 closure: **#105**  
Implementation/evidence merge: **#106**  
Merged commit: `4989547227ac9fe74e3bcc7fd46b61eeb95ffae4`

## Scope

M2-F closes Android route/VPN recovery on the app's actual selected default
network. It does not select a production transport (M2-G) and does not make
representative physical-device performance claims.

The closed stack is:

```text
F0 actual route/VPN feasibility
  -> F1 exact route-bound external execution
  -> F2 one RecoveryChain across default-route loss/replacement
  -> F3 VPN continuity + event-driven explicit direct override
  -> F4 canonical scenario/run binding + retained evidence replay
```

## Final review corrections

The final PR review found and fixed two evidence-integrity gaps before merge:

1. the Media Lab origin trace was used by the F4 oracle but was not part of
   `evidence-index-v1`; the exact trace used for acceptance is now retained as
   `origin-trace.jsonl` and content-addressed with the rest of the bundle;
2. `semanticDigest` was initially computed from the expected case template;
   it is now built from the verified runtime route/recovery artifacts, normalized
   to abstract epoch labels, compared against the canonical sequence, and only
   then hashed.

The exact reviewed PR head was
`a19161961594b71e1208341e1a00b08c4c7f1f77`. All required checks were green
before squash merge.

## Merged-main retained runs

All runs below use merge commit
`4989547227ac9fe74e3bcc7fd46b61eeb95ffae4`.

| Evidence | Workflow run | Artifact | Bytes | SHA-256 | Result |
| --- | ---: | ---: | ---: | --- | --- |
| F2 default-route recovery | 36546265321 | 11022926697 `m2-f2-route-recovery` | 222579 | `105479d473b1b283987cec677dc74aa00162dc304a3d4a94a07c1223a97d14b5` | PASS |
| F3 VPN continuity + override | 36546265498 | 11022732451 `m2-f3-vpn-continuity` | 322478 | `c7d51f1c30b209af9352ea87fd4f87142679646234654304e6ae0268703629db` | PASS |

Merged-main supporting regressions also passed:

- F0 Route/VPN Feasibility — run **36546265314**;
- M2-E Network Fault Harness — run **36546265346**;
- Verify — run **36546265315**;
- Android Smoke — run **36546265230**.

## Canonical cases

| Case | Scenario SHA-256 | Semantic replay SHA-256 | M2 gates |
| --- | --- | --- | --- |
| `F2_DEFAULT_ROUTE_LOSS_RESTORE` | `05e0359cb33d864600e98547776b98b5a436f372c99ab325e7f0797c80d42c5a` | `a152627697e53500d3afbb394c2bbfad4008e89e683939b54c4163d3dd94ef6f` | ACC-04/05/06 PASS; ACC-03 N/A |
| `F3_VPN_CONTINUITY_RESTORE` | `34c90d6d66a6401d7fb28180668a17e94b0829ac9383f5e0599db2af08982adc` | `5ea340f0dd689b8f127e0971d8e2df50102feec38719835d887ece6bf488fcbb` | ACC-03/04/05/06 PASS |
| `F3_VPN_CONTINUITY_DIRECT_OVERRIDE` | `6a827ed39763162f5b29b0c41785176319476099aacf741b112b1406185f0163` | `b8781bb89434d454f9c8639b3e5fd624107bacc2b2126082ea5a4729bfd67bf4` | ACC-03/04/05/06 PASS |

## Post-retention independent verification

The retained ZIPs were downloaded after the merged-main runs completed and
checked independently of the workflow status.

For both bundles:

- locally recomputed ZIP SHA-256 equals the GitHub artifact digest above;
- every file listed by each `evidence-index.json` exists and its SHA-256
  matches the indexed digest;
- `origin-trace.jsonl` is present in each canonical evidence index;
- every `run-manifest.json.gitCommit` equals the merged commit;
- canonical SHA-256 of `scenario.json` equals both the run-manifest scenario
  hash and F4 summary/index scenario hash;
- F4 summary and evidence index agree on semantic replay digest;
- `runId` and `sessionId` agree across case, manifest, index and summary;
- all pre-existing `persistedExtentsBefore` identities are byte-for-byte
  identical in `persistedExtentsAfter`;
- all three F4 verification summaries report `PASS`.

Each canonical case indexes **11** source/oracle artifacts.

## Acceptance conclusion

M2-F satisfies the route/VPN-owned runtime evidence for:

- **M2-ACC-03 Route Privacy** — VPN-started sessions do not silently fail open
  to a direct replacement; explicit direct override is separately attributable;
- **M2-ACC-04 Persisted Media Independence** — route/VPN transitions preserve
  complete pre-existing committed extent identity;
- **M2-ACC-05 Failure Separation** — observation, classification, decision and
  executed action remain independently replayable;
- **M2-ACC-06 Bounded Recovery Lineage** — route changes, VPN changes and gate
  wakeups do not create a fresh RecoveryChain budget.

M2-F is complete. The next owning slice is **M2-G — Transport Evidence
Evaluation**.
