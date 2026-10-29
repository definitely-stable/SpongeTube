# M3-A2-C2-D5 — canonical post-format empty-formats discriminator

Executed: **2026-10-29 UTC**. Issue #158 (parents #144/#50).
Status: **CANONICAL EVIDENCE VERIFIED; D5 COMPLETE AS A DIAGNOSTIC SLICE.
M3-A2 remains NOT_MADE_M3_A2.**

## Exact provenance

- Implementation [PR #174](https://github.com/definitely-stable/SpongeTube/pull/174)
  squash merged to `3dbb570ab2ee53d185f31a5790dbc09e03acb4b7` only after Linux+Windows Verify SUCCESS
  and a separate pinned yt-dlp terminal hook preflight SUCCESS.
- Canonical GitHub-hosted single-use provider-facing
  [run #40911692927](https://github.com/definitely-stable/SpongeTube/actions/runs/40911692927)
  completed **SUCCESS** on exact `main`.
- Original [artifact #12914735795](https://github.com/definitely-stable/SpongeTube/actions/runs/40911692927/artifacts/12914735795),
  name `m3-a2-c2-d5-terminal-branches`. GitHub Action original ZIP
  SHA-256 `d3716d8d725dc3f83813858496f4fa2e08efd02c51506693bc42a464aa547d81`. Independently downloaded original
  archive SHA-256 was **identical**.
- Exact runner `ubuntu24@20261026.354.1`. Pinned reference
  yt-dlp `2026.10.21` with installed RELEASE_GIT_HEAD
  `8cdf4fea48ecf4064387375301d7f294ebdb17c1`.
- Original D3/D4 registry, cases, client profiles, network limits, no-JS,
  no-token and anonymous research constraints remained unchanged.

## Independent source-backed ZIP audit

Without invoking the experiment producer or its `verify_bundle`, the
downloaded archive was checked for its exact six-member set, five SHA-256
manifest entries (and manifest file SHA-256), single canonical commit,
runner/oracle binding, four exact ordered controls, case fingerprint +
client profile equality to frozen D3, all nested typed D3 response events
and full 16-entry D4 stage sequence, closed D5 terminal fields, aggregate
summary derivation, and no raw case IDs or URLs. All checks PASS.

Immutable machine-readable digest and finding ledger:
`.work/evidence/m3-a2/c2-d5-canonical-v1.json`.
The original ZIP remains in the GitHub Actions artifact service.

## Four canonical observations

| Control | Formats after `_list_formats` | `raise_no_formats` calls | `report_drm` calls | Metadata requests |
| --- | --- | ---: | ---: | ---: |
| V0 / WEB_EMBEDDED | EMPTY | 1 | 0 | 4 |
| V2 / WEB_EMBEDDED | EMPTY | 1 | 0 | 4 |
| V0 / TV | EMPTY | 1 | 0 | 4 |
| V2 / TV | EMPTY | 1 | 0 | 4 |

Every frozen control had **one** non-exceptional return of
`_list_formats`, then **one** intercepted `raise_no_formats` call,
with **no** `report_drm` call. All nested D4 stage traces retained
the same 16 typed events: webpage/data/player response stages returned,
`FORMAT_SELECTION` returned, then outer `VIDEO_EXTRACT` raised.
Each control made 4 HTTP_2XX metadata requests, MIME
`HTML,HTML,TEXT_OTHER,JSON`, total **16/16 2xx**,
**0/4 completed metadata extractions**, **0 media requests**.
Trusted inner error type remains `YTDLP_EXTRACTOR_ERROR` wrapped
by `YTDLP_DOWNLOAD_ERROR`.

## Interpretation and hard limits

The **observed terminal path** is now established for all four fixed
configurations: yt-dlp supplied an empty formats list to
`_real_extract`, and that call then invoked `raise_no_formats`.
The upstream `_real_extract` implementation contains a
`if not formats` branch and this observation is consistent with it.
The direct no-formats hook makes the mechanism stronger than D4's
post-format stack inference.

This **does not** determine why the formats collection was empty. A
returned player response, HTTP_2XX status, or JSON MIME does not imply
the response carried usable stream metadata. Reasons may include absent
`streamingData`, unusable or filtered formats, challenge/runtime policy,
provider state or egress treatment. D5 did not read response values,
inspect parser warnings or log reason/error strings. No **DRM-reporting**
path was observed: `report_drm=0` does not prove the content is
universally DRM-free.

**Do not infer** that JS/EJS, PO tokens, authentication, proxy changes or
anti-abuse bypass is necessary. D5 did not test any of them. No full
18-cell matrix, V1 split audio/video, media Range behavior, M3-A3 choice
or M3-B adapter adoption is approved. Policy #52 remains an independent
production license/terms/distribution gate.
`globalDecision=NOT_MADE_M3_A2`.

## Best next research step: D6 (not yet implemented)

Predeclare a new **source-structural, privacy-closed format-supply
diagnostic** inside the pinned reference: classify whether any trusted
player response contains a `streamingData` object and whether
`formats`/`adaptiveFormats` lists are present (only boolean/size
buckets, never content). Separately record bounded filter/rejection
class counters from known extractor branches without solving challenges,
capturing URLs or enabling new token/runtime functionality. This would
distinguish **no source formats supplied** from **formats supplied but
discarded**. It must not mutate D0–D5 or authorize production adoption.

Evidence cleanup removes consumed `c2-d5-run-v1.trigger` and its
workflow `push` event atomically, preserving manual-only dispatch.
