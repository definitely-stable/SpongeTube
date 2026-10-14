# SpongeTube M3-A2-C2-D1 — typed HTTP response and extractor-stage probe

Date: 2026-10-14. Status: **versioned experiment implementation; live results pending**.
Owner: #158, parent #144/#50. Previous observations: C1 #152, C2-D0 #160/#161.

## Why another frozen experiment

C2-D0 observed four incomplete metadata extractions and zero exceptions
escaping its instrumented `YoutubeDL.urlopen` boundary. This **does not**
establish successful HTTP responses; an extraction may return an unusable
HTTP response or raise a higher-layer error after the request.

D1 changes **only observation granularity**, not provider identity, client
profile, oracle release, requested media, network route or JavaScript mode.

## Exact controls and bounds

Committed source-of-truth: `.work/m3-a2/c2-d1-plan-v1.json`.
The plan is an exact extension of D0's plan, itself derived from the C1
four-cell subset of the original 18-cell M3-A2 plan.

Execution order:

1. V0 / WEB_EMBEDDED
2. V2 / WEB_EMBEDDED
3. V0 / TV
4. V2 / TV

Use exactly the pinned case source, fingerprinted IDs and yt-dlp
2026.08.19 / 594bd50c2c78ac432f81600d309fdc4e0a92d82c.
One GitHub-hosted Ubuntu runner and exact GITHUB_SHA per batch.
At most 32 metadata request attempts per cell, 128 total;
the attempt is counted **before** any request I/O.
Zero media reads, zero hidden case retries or alternative profiles.

No JS/EJS, remote components, PO tokens, credentials, account sessions,
request disguise, explicit proxy rotation, VPN bypass or download.

## Typed observability contract

D1 artifacts are **not** M3-A1 m3-a-probe-v1 artifacts and cannot be
substituted into the 18-cell compatibility matrix. They extend the validated
offline C2 diagnostic vocabulary with:

- `responseEvents`: an exhaustive, ascending, exactly-once outcome for
  every counted `YoutubeDL.urlopen` invocation.
- Successful `RETURNED` events: bounded `HTTP_1XX` ... `HTTP_5XX`
  or `UNKNOWN`; safe Content-Type family `JSON`, `HTML`, `MEDIA`,
  `TEXT_OTHER`, `OTHER` or `UNKNOWN`.
- `EXCEPTION` events: closed typed transport category, same ordinal and
  classification as the base `networkFailures` record.
- `extractorStage`: `MAPPING_RETURNED`, `NON_MAPPING_RETURNED`,
  `EXTRACT_INFO_RAISED` or `INITIALIZATION_FAILED`.
- `extractorErrorType`: `NONE`, `YTDLP_EXTRACTOR_ERROR`,
  `YTDLP_DOWNLOAD_ERROR` or `OTHER_EXCEPTION`.

A successful HTTP class is recorded only for an object of pinned
`yt_dlp.networking.common.Response` type. An object merely containing
`status=200` cannot forge successful HTTP evidence. HTTP observation
describes only the **returned response object**, not an unobserved redirect
chain or the meaning of its body. An extractor error type is recognized by
pinned reference exception type; a DownloadError wrapper is *not* interpreted
as an actual token, JS or provider requirement.

No response body, raw Content-Type header, URL, referrer, request method,
host, IP, HTTP status reason, cookies, visitor identifiers, raw content IDs,
signed URLs or exception messages are serialized. All structured fields
are closed and bounded; extra fields fail validation. The complete portable
bundle is additionally scanned for all original case IDs prior to upload.

## Independent audit and acceptance

Producer: `scripts/measurement/m3_c2_d1.py run`.
Plan check: `scripts/measurement/m3_c2_d1.py validate-plan`.
Audit: `scripts/measurement/m3_c2_d1.py verify`.

Audit independently recomputes summary counters, checks all four
case/fingerprint/profile bindings, single SHA/runner, SHA-256 for all
five payload files, fixed total budgets, zero media probes, exact control
set, redaction, and `globalDecision=NOT_MADE_M3_A2`.
Falsification tests:
`scripts/measurement/tests/test_m3_c2_d1.py`.

First canonical run is armed by the one-shot source marker
`.work/m3-a2/c2-d1-run-v1.trigger`, but it can run only after a reviewed,
green PR merges to `main`. After canonical evidence audit, remove **both**
the marker and the workflow `push` event in a single cleanup PR; retain
the original ZIP digest and per-file evidence ledger.

Live provider responses are **not** an acceptance gate for deterministic PR
CI; their content can legitimately yield `UNCLASSIFIED`.

## Decision discipline

If 2xx response events are observed but metadata extraction still fails,
investigate extractor/challenge interpretation as a competing hypothesis.
If 403/429 or other non-2xx responses occur, record exact provenance and
avoid extrapolating beyond those cells. If response status is unknown,
report UNKNOWN, not success or rejection. Never treat absence of an error
as proof of anonymous delivery viability.

D1 does not qualify V1, complete M3-A2, select M3-A3, or implement M3-B.
The license/distribution decision #52 remains a blocking production gate;
any future EJS runtime experiment requires its own explicitly frozen design.
