# M3-A2 — canonical V1 r2 primary-lane qualification failure

Date: 2026-10-12 (audit); live run: 2026-10-11 UTC.
Scope: issue #144, exact-main commit `23665f64c9a22b0e7bc6131d41b7a475961d9ff6`.
Workflow: https://github.com/definitely-stable/SpongeTube/actions/runs/38155051380
Artifact: https://github.com/definitely-stable/SpongeTube/actions/runs/38155051380/artifacts/11707558975
Uploaded artifact SHA-256: `1f9f8bfca187b5387edc1086e5287fb085eec12cb506d2aff5fcf92e3e6ad8c2`.
Artifact retention is 30 days. Run provenance must not be replaced by a later rerun.

## Observed, not inferred

Exactly six V1 r2 primary-lane probes were retained on the frozen plan v2, followed by fail-closed stop.
All six returned `INCONCLUSIVE`, `mediaLayout=UNKNOWN`, `https=UNKNOWN`, no
successful direct-media probe and no observed typed rejection/throttling. Each metadata
extraction was incomplete; the 32-request ceiling was not exhausted.

| Lane | Metadata requests | Media probes | Qualification |
| --- | ---: | ---: | --- |
| WEB_EMBEDDED | 4 | 0 | NOT_MET |
| TV | 4 | 0 | NOT_MET |
| ANDROID_VR | 2 | 0 | NOT_MET |
| MWEB | 3 | 0 | NOT_MET |
| WEB | 1 | 0 | NOT_MET |
| WEB_SAFARI | 3 | 0 | NOT_MET |
| **Total** | **17** | **0** | **0/6** |

The 12 V0/V2 matrix cells were intentionally **not run**; no complete compatibility
matrix exists. The v1 summary's key `remainingCellsExecuted=12` is a known
misnomer: it counts unexecuted cells, not executed probes. This report uses
`unexecutedCells` instead. Do not silently rewrite original evidence.

## Independent reproducibility

Download the exact workflow artifact ZIP and run:

```sh
python3 scripts/measurement/m3_a2_negative_audit.py \
  --bundle /path/to/m3-a2-canonical-matrix.zip \
  --registry .work/m3-a2/case-registry-v1.json \
  --plan .work/m3-a2/matrix-plan-v2.json \
  --expected-bundle-sha256 1f9f8bfca187b5387edc1086e5287fb085eec12cb506d2aff5fcf92e3e6ad8c2 \
  --output /tmp/m3-a2-negative-audit.json
```

The independent audit checks the exact downloaded ZIP digest, recomputes the V1 gate without using the producer's qualification helper, and validates the six existing `m3-a-probe-v1` documents,
exact registry, source commit, lane/profile bindings, paired qualification copies,
all six failure reasons and SHA-256 of each retained probe. It deliberately cannot
promote `QUALIFICATION_NOT_MET` into a successful matrix or global provider decision.

## Research decision

**NO-GO for claiming V1 r2 qualification from this exact no-token/no-JS
reference setup.** This is **not** a NO-GO for all YouTube anonymous delivery.

Two consecutive V1 candidates produced incomplete metadata on WEB_EMBEDDED;
all six lanes of the second candidate then failed without any media bytes.
The common extraction/oracle/egress failure mode is now a serious competing
explanation to candidate-specific unsuitability.

Next controlled research slice should:
1. retain this immutable negative result and avoid silently changing v2;
2. add an explicitly versioned diagnostic protocol that distinguishes an
   extraction/egress/oracle failure from real provider capability absence using
   typed, redacted diagnostics; use a separate pinned reference if needed;
3. predeclare V0 and V2 control observations independent of whether V1 qualifies,
   in a new plan revision with bounded requests and no duplicate hidden probes;
4. freeze an M3-A3 architecture decision **only after** sufficient positive/negative
   capability evidence exists, and address policy issue #52 before M3-B.

No production extractor, token/challenge runtime, route bypass, account use,
new Android dependency, release/performance claim or global provider inference
is authorized by this record.
