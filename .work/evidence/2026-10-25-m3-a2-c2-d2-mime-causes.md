# M3-A2-C2-D2 — canonical typed MIME and nested extractor cause evidence

Date: **2026-10-25 UTC** · owner: #158 · parent: #144/#50.
Status: **CANONICAL EVIDENCE VERIFIED; PROVIDER FEASIBILITY NOT DECIDED**.

## Exact immutable provenance

- Implementing PR: [#165](https://github.com/definitely-stable/SpongeTube/pull/165), squash/main `2cb34cef638dd07ea6e299b6290c7b7a5f2bffd7`.
- Exact-main GitHub-hosted run: [#40205634582](https://github.com/definitely-stable/SpongeTube/actions/runs/40205634582) — **SUCCESS**, attempt 1.
- Original [artifact #12603807239](https://github.com/definitely-stable/SpongeTube/actions/runs/40205634582/artifacts/12603807239), name `m3-a2-c2-d2-typed-response`.
- Original ZIP SHA-256: `f047e77b5a1881199f750fc0abc2ea15512ce56c53a3606d9a8f55200ce4eb18`. It exactly matches GitHub Actions' advertised uploaded-artifact digest.
- Runner: `ubuntu24@20261023.334.3`.
- Pinned yt-dlp: `2026.08.19` / `594bd50c2c78ac432f81600d309fdc4e0a92d82c`.
- Registry: `m3-a2-cases-2026-10-11-r2`; exact inherited D1 4-control plan, D2 `m3-a2-c2-d2-mime-causes-2026-10-25-v1`.
- Compact independent evidence digest/provenance ledger: `.work/evidence/m3-a2/c2-d2-canonical-v1.json`.

The original ZIP was downloaded separately and audited without importing
the producer's verifier. Archive SHA-256, the five manifest-listed SHA-256
entries, six exact ZIP members, four fixed case fingerprint/revision/profile
identities, one exact source commit and runner, fixed oracle/mode, closed
typed response and nested-cause fields, budgets, summaries, negative provider
decision and basic privacy-field constraints all checked **PASS**. The
original archive remains a GitHub Actions artifact; the repository retains
only the compact content-addressed digest ledger and this interpretation.

## Direct bounded observations

| Frozen control | Requests | Returned response statuses | MIME families | Extractor failure | Nested typed cause |
| --- | ---: | --- | --- | --- | --- |
| V0 / WEB_EMBEDDED | 4 | 4 × HTTP_2XX | HTML, HTML, TEXT_OTHER, JSON | YTDLP_DOWNLOAD_ERROR | YTDLP_EXTRACTOR_ERROR |
| V2 / WEB_EMBEDDED | 4 | 4 × HTTP_2XX | HTML, HTML, TEXT_OTHER, JSON | YTDLP_DOWNLOAD_ERROR | YTDLP_EXTRACTOR_ERROR |
| V0 / TV | 4 | 4 × HTTP_2XX | HTML, HTML, TEXT_OTHER, JSON | YTDLP_DOWNLOAD_ERROR | YTDLP_EXTRACTOR_ERROR |
| V2 / TV | 4 | 4 × HTTP_2XX | HTML, HTML, TEXT_OTHER, JSON | YTDLP_DOWNLOAD_ERROR | YTDLP_EXTRACTOR_ERROR |
| **Total** | **16** | **16 returned 2xx** | **16 typed MIME families** | **4 incomplete** | **4 typed inner causes** |

All `networkFailures=[]`, `extractionCompleted=false`,
`extractorStage=EXTRACT_INFO_RAISED`, base `terminalClass=UNCLASSIFIED`,
`totalMediaProbes=0`. No account, JS/EJS, token provider, remote components,
retry/route bypass or full media download was permitted.

## Scientific interpretation

D1's 16 `Content-Type=UNKNOWN` fields were a **measurement blind spot**:
the pinned `Response.headers` uses `email.message.Message`, not
`collections.abc.Mapping`. D2 correctly reduces those responses to closed
MIME families. The fourth returned response was observed as `JSON` for all
four controls, yet all four metadata extractions remained incomplete.
An HTTP 2xx plus JSON Content-Type is **not proof of JSON body correctness,
valid provider metadata, adequate parser inputs or usable media**.

D2 also distinguished the outer `DownloadError` wrapper from an inner
trusted `ExtractorError` **type**. This narrows diagnostic location but
does not identify the cause within the extractor, prove the availability
or necessity of JS/EJS/PO tokens, exonerate the egress environment, or establish
a general YouTube access restriction. No response bodies were retained; the
experiment cannot decide content validity. All four outcomes share a frozen
oracle, runner and network environment, so they are **correlated observations**,
not four independent reliability samples.

**Global outcome: `NOT_MADE_M3_A2`.** No V1 split A/V qualification, no
completed 18-cell matrix, no M3-A3 architecture decision and no M3-B adoption.
Licensing/distribution policy [#52](https://github.com/definitely-stable/SpongeTube/issues/52) remains a gate.

## Next separately predeclared experiment

Before incorporating a new production extractor, design a bounded
**version-paired oracle comparison**, e.g. pinned
`yt-dlp 2026.08.19` vs an independently pinned newer release, at the same
V0/V2 controls, anonymous no-JS/no-token mode and comparable environment.
Record oracle as the **only intended changing treatment**, with order effects,
network/provider changes, versioned fingerprints, source/revision/hashes and
strict request limits. If evidence requires EJS/runtime, isolate it in a
second separately reviewed comparator after #52; do not enable it silently.

After this canonical run, remove the consumed one-shot
`.work/m3-a2/c2-d2-run-v1.trigger` and the workflow's `push` trigger
atomically in one reviewed cleanup PR. Keep manual `workflow_dispatch`.
