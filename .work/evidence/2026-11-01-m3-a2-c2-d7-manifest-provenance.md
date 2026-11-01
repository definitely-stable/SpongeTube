# M3-A2-C2-D7 — canonical parsed-source and manifest provenance

Executed: **2026-11-01 12:18–12:19 UTC**. Owner: #158, parents #144/#50.
Status: **CANONICAL EVIDENCE INDEPENDENTLY VERIFIED; D7 COMPLETE.**
Global milestone decision: **NOT_MADE_M3_A2**.

## Exact execution and audit

- D7 implementation [PR #179](https://github.com/definitely-stable/SpongeTube/pull/179), squash merged at `e33d40a305dc177635140a397720c8e7388127f5`, after 31 successful/skipped CI checks, including Linux/Windows, Android and pinned yt-dlp preflight.
- One-shot exact-main marker [PR #180](https://github.com/definitely-stable/SpongeTube/pull/180), squash merged at `4cad7091fcbf160464ca7efe7b0360ffa18f60d1` after Linux/Windows Verify and D7 preflight SUCCESS.
- Canonical GitHub-hosted [run #41305878254](https://github.com/definitely-stable/SpongeTube/actions/runs/41305878254): SUCCESS, event `push`, exact `head_sha=4cad7091fcbf160464ca7efe7b0360ffa18f60d1`, pinned yt-dlp 2026.10.21 / RELEASE_GIT_HEAD `8cdf4fea48ecf4064387375301d7f294ebdb17c1`. Runner: `ubuntu24@20261026.354.1`.
- Original [Actions artifact #13083550309](https://github.com/definitely-stable/SpongeTube/actions/runs/41305878254/artifacts/13083550309), `m3-a2-c2-d7-manifest-provenance` (7,447 ZIP bytes).
- Original ZIP SHA-256 independently downloaded and computed: `1261df13c5d5cd4d03704e92bf47c6f88b782303011cc2e933ca3efefaac86d1`, **identical to GitHub's published digest**. Manifest SHA-256: `d2af52a0af5ae05841baf228fc825f80412e958e76f436624b6574215aa62167`.
- Original six-file ZIP audited using an independent Python verifier with **no import of D7 producer/validators**. Verified 5 member hashes; nested D3→D4→D5→D6→D7 closed schemas and exact source/runner/plan; original D6 case/client identity; typed D4 stage events; format/manifest counters and cross-stage equality; SHA-256 digests, privacy and independently recalculated D7 summary. All PASS. Machine ledger: `.work/evidence/m3-a2/c2-d7-canonical-v1.json`.

## Canonical observations (all four frozen B arms)

| Case | Parsed embedded before yt-dlp strip: `streamingData` | API return `streamingData` | Parsed embedded / API HLS,DASH | Selected candidates | Metadata |
| --- | --- | --- | --- | --- | ---: |
| V0 / WEB_EMBEDDED | ABSENT | ABSENT | ABSENT / ABSENT | NONE | 4 |
| V2 / WEB_EMBEDDED | ABSENT | ABSENT | ABSENT / ABSENT | NONE | 4 |
| V0 / TV | ABSENT | ABSENT | ABSENT / ABSENT | NONE | 4 |
| V2 / TV | ABSENT | ABSENT | ABSENT / ABSENT | NONE | 4 |

For every control:
- `_search_json(_YT_INITIAL_PLAYER_RESPONSE_RE,...)` **returned a parsed embedded initial object** with `streamingData=ABSENT`, before the pinned upstream deliberately creates a copy with `streamingData=None`.
- One `_extract_player_response` API return also had `streamingData=ABSENT`.
- Neither parsed embedded nor API returns contained the `hlsManifestUrl` or `dashManifestUrl` fields under `streamingData`.
- Two normalized and selected response structures persisted: `streamingData=NULL`, followed by a synthetic nonempty `streamingData=ONE`, but **neither contained ordinary candidate lists nor HLS/DASH manifest fields**.
- `selectedInputCandidateState=NO_CANDIDATES_IN_SELECTED_INPUT`; 0 emitted formats, 0 PO skip reports. The nested D5 oracle returned EMPTY and called `raise_no_formats` once. Every cell had four HTTP_2XX metadata responses and no completed metadata extraction.

Aggregate **16 metadata requests (all 2xx), 0 media requests**, exact source/runner/account-free/no-JS/no-PO-token mode, within the frozen budgets.

## Scientific conclusion and next decision gate

D7 eliminates a specific local hypothesis for these controls: the absence of `streamingData` was **already observable in the parsed embedded source object before the local deliberate strip**, and independently at the API extractor return before normalization. Thus the empty format lists cannot be attributed *solely* to the upstream copy/normalization branch. D7 also found no HLS/DASH manifest alternative in these parsed objects or selected input.

This does **not** prove absence from the raw HTTP response bytes, nor identify the cause of the missing streaming metadata: provider configuration, egress treatment, access policy, parsing/version drift and other effects remain unseparated. HTTP 2xx is not playable media. Missing PO skip calls is not proof that token-related policy can never matter.

**Recommendation:** mark D7 diagnostic COMPLETE and **STOP serial source-shape probes in the same frozen four-cell environment**. D0–D7 have converged on a repeated terminal condition with no playable media and no mechanism-specific intervention justified. Reopen only for a *materially new, ethically and technically supported evidence source* (different explicitly approved environment/reference with a predeclared control) rather than an unbounded D8. Do not introduce JS/EJS, PO-token automation, anti-abuse bypass or a production YouTube adapter based on this negative evidence. M3-A3/M3-B remain blocked; independent policy gate #52 unchanged.

Cleanup in the accompanying PR atomically removes the consumed D7 marker and `push` event. The provider-facing D7 workflow becomes **manual-only**, as required.
