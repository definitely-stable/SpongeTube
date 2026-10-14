# M3-A2-C2-D1 — canonical typed HTTP response and extractor-stage evidence

Date: **2026-10-14 UTC**. Owner: #158, parent: #144.
Protocol: `m3-a2-c2-d1-responses-2026-10-14-v1`.

## Immutable canonical provenance

- Implementation PR #162, squash/main `aadd92be9446cf15b08c99b78992546c6f74bee3`.
- Exact-main GitHub-hosted workflow [#38556880002](https://github.com/definitely-stable/SpongeTube/actions/runs/38556880002): **SUCCESS**, first attempt.
- Uploaded [artifact #11889828319](https://github.com/definitely-stable/SpongeTube/actions/runs/38556880002/artifacts/11889828319), name `m3-a2-c2-d1-typed-response`.
- Original archive SHA-256: `446a2502f0f085930bcbded379eb3809c75aadecca851bd6d2b4794b6c24a181`.
- Runner `ubuntu24@20261004.327.1`; yt-dlp `2026.08.19` / release HEAD `594bd50c2c78ac432f81600d309fdc4e0a92d82c`.
- Frozen case registry `m3-a2-cases-2026-10-11-r2`, exact D0-derived four-cell D1 plan.

The original ZIP was downloaded independently. Its SHA-256 equals the GitHub
Actions artifact digest. The audit independently verified all five manifest
entries against their content SHA-256, expected exactly four probe identities,
their case/fingerprint/profile bindings, exact commit/runner/reference identity,
closed request events, zero media reads, mode invariants, and matching summary.
A machine-readable digest ledger is in
`.work/evidence/m3-a2/c2-d1-canonical-v1.json`.

| Frozen cell | Requests | Returned HTTP_2XX | Extractor stage | Typed wrapper |
| --- | ---: | ---: | --- | --- |
| V0 / WEB_EMBEDDED | 4 | 4 | EXTRACT_INFO_RAISED | YTDLP_DOWNLOAD_ERROR |
| V2 / WEB_EMBEDDED | 4 | 4 | EXTRACT_INFO_RAISED | YTDLP_DOWNLOAD_ERROR |
| V0 / TV | 4 | 4 | EXTRACT_INFO_RAISED | YTDLP_DOWNLOAD_ERROR |
| V2 / TV | 4 | 4 | EXTRACT_INFO_RAISED | YTDLP_DOWNLOAD_ERROR |
| **Total** | **16** | **16** | **0 completed** | **4 wrappers** |

Every response event was a **returned** object of the pinned yt-dlp
`networking.common.Response` type with numeric status in the **2xx family**;
none was recorded as an exception escaping `YoutubeDL.urlopen`.
All four extractions ended with the pinned `yt_dlp.utils.DownloadError`
wrapper. Its original inner cause was **not** resolved by the D1 protocol;
all four retained base `terminalClass=UNCLASSIFIED`.

All 16 Content-Type families are **UNKNOWN**. This is not evidence that
responses omitted Content-Type. Source inspection of pinned
`yt_dlp.networking.common.Response` confirms its `headers` field is an
`email.message.Message`. The D1 capture accepted the family only for a
`collections.abc.Mapping`, so the observed UNKNOWN is an **instrumentation
blind spot**. Do not relabel it with presumed JSON/HTML or infer body semantics.
Preserve D1 code and its original archive as the immutable measurement.

## What the result supports — and what it does not

The earlier C1 and D0 runs had 4/4 incomplete metadata extractions with no
recorded HTTP errors; D1 positively observes 16 successful HTTP **status
classes** at the returned-response boundary. It therefore narrows the failure
search away from simple 403/429 rejection at that boundary on these exact cells.

It **does not** establish response-body validity, absence of redirects,
successful extraction, JS/EJS necessity, PO-token requirements, media
capability, V1 split A/V qualification or YouTube-wide feasibility.
The four cells share pinned oracle and runner and are correlated experiments,
not four independent random estimates of provider reliability.
The global decision remains `NOT_MADE_M3_A2`.

## Next falsifiable diagnostic step

Before comparing alternative runtimes, freeze **C2-D2** as a separate
versioned experiment and fix only the measurement blind spots:

1. Read Content-Type via pinned `Response.headers.get("Content-Type")`
   on the confirmed response type, still reducing immediately to a closed
   safe family and never retaining the raw header.
2. Distinguish only trusted nested yt-dlp exception **types** in
   `DownloadError` at the extractor boundary, with a finite causal traversal
   and explicit UNKNOWN; never parse exception strings or provider text.
3. Keep the same V0/V2 × WEB_EMBEDDED/TV cases, no JS/EJS, no remote
   components or credentials, and the original 32-per-cell request ceiling.
4. Add falsification tests for forged headers, nested errors, cyclic causes,
   PII/redaction and invalid provenance. Require exact GitHub-hosted evidence.
5. If typed results still do not identify the failure, separately review
   an explicitly pinned EJS/runtime comparison. Policy #52 must be addressed
   before dependency or distribution decisions.

No production adapter/transport/storage change is justified by D1.

## One-shot trigger cleanup

The D1 exact-main one-shot marker was consumed by workflow #38556880002.
The same cleanup PR removes the marker **and** the workflow `push` event,
returning to manual dispatch. Removal must be atomic in the squash merge
to avoid an accidental second live-provider run.
