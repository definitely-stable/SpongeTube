# M3-A2-C2-D4 — canonical typed YoutubeIE stage trace

UTC run date: **2026-10-25**. Issue #158, parent #144/#50.
Status: **CANONICAL D4 EVIDENCE VERIFIED. ROOT CAUSE NOT YET DECIDED.**

## Provenance and independent evidence audit

- Implementation [#170](https://github.com/definitely-stable/SpongeTube/pull/170)
  merged at `eb80c9ccc55183d080dc7e3956e9bb9d1adb440a`.
- Initial exact-main run
  [#40232527194](https://github.com/definitely-stable/SpongeTube/actions/runs/40232527194)
  failed closed during observation without a verified artifact. The provider
  request count for that failed attempt is **UNKNOWN**: no scientific
  conclusion is drawn from it.
- Follow-up [#171](https://github.com/definitely-stable/SpongeTube/pull/171)
  fixed the actual pinned yt-dlp class identity: upstream
  `youtube/__init__.py` sets `YoutubeIE.__module__` to
  `yt_dlp.extractor.youtube` for plugin overrides, although the class
  originates in `._video`. Offline exact pinned-hook PR workflow
  [#40233244903](https://github.com/definitely-stable/SpongeTube/actions/runs/40233244903)
  passed. Linux/Windows Verify also passed. Fix merged at `bdb93660648550fda440eb445881a02be8810098`.
- **Successful canonical provider-facing exact-main run:**
  [#40233961424](https://github.com/definitely-stable/SpongeTube/actions/runs/40233961424).
  GitHub-hosted Ubuntu, attempt 1, overall **SUCCESS**.
- Original [artifact #12614560774](https://github.com/definitely-stable/SpongeTube/actions/runs/40233961424/artifacts/12614560774),
  name `m3-a2-c2-d4-extractor-phases`,
  archive SHA-256:
  `27b087cd49c8188c1fb6bb878d197d5e91d591c40838de264f03681a9df66e8d`.
- Pinned oracle yt-dlp `2026.10.21`,
  `RELEASE_GIT_HEAD=8cdf4fea48ecf4064387375301d7f294ebdb17c1`.
  Exact runner `ubuntu24@20261023.334.3`; git commit `bdb93660648550fda440eb445881a02be8810098`.

The original ZIP was independently downloaded and audited **without using
the D4 producer/validator**. SHA matched the GitHub Actions published ZIP
digest. All six archive members, five listed SHA-256 manifest entries,
four case revisions/fingerprints/client bindings, pinned oracle and runner,
closed event schema and balanced nested stacks, exact derived summary,
metadata budgets, HTTP response classes and MIME families, and omission of
forbidden field types were checked **PASS**.
The original ZIP remains in GitHub Actions. Machine-readable SHA/observation
ledger: `.work/evidence/m3-a2/c2-d4-canonical-v1.json`.

## Four independently validated control traces

| Frozen control | Metadata attempts | HTTP | MIME | Inner phase completion | Raised phase |
| --- | ---: | --- | --- | --- | --- |
| V0 / WEB_EMBEDDED | 4 | 4 × HTTP_2XX | HTML, HTML, TEXT_OTHER, JSON | All seven returned | VIDEO_EXTRACT |
| V2 / WEB_EMBEDDED | 4 | 4 × HTTP_2XX | HTML, HTML, TEXT_OTHER, JSON | All seven returned | VIDEO_EXTRACT |
| V0 / TV | 4 | 4 × HTTP_2XX | HTML, HTML, TEXT_OTHER, JSON | All seven returned | VIDEO_EXTRACT |
| V2 / TV | 4 | 4 × HTTP_2XX | HTML, HTML, TEXT_OTHER, JSON | All seven returned | VIDEO_EXTRACT |

Each trace contained exactly **16** ordered and fully balanced typed events:

```text
VIDEO_EXTRACT ENTER
  INITIAL_EXTRACT ENTER
    WEBPAGE_FETCH ENTER / RETURN
    INITIAL_DATA ENTER / RETURN
    CLIENT_SELECTION ENTER / RETURN
    PLAYER_RESPONSES ENTER
      PLAYER_RESPONSE ENTER / RETURN
    PLAYER_RESPONSES RETURN
  INITIAL_EXTRACT RETURN
  FORMAT_SELECTION ENTER / RETURN
VIDEO_EXTRACT RAISE
```

The stage labels correspond to pinned upstream methods
`_real_extract`, `_initial_extract`, `_download_initial_webpage`,
`_download_initial_data`, `_get_requested_clients`,
`_extract_player_responses`, `_extract_player_response` and
`_list_formats`, respectively.

All four yielded outer `YTDLP_DOWNLOAD_ERROR`, trusted nested
`YTDLP_EXTRACTOR_ERROR`, no completed metadata mappings and
**zero media reads**. There were 16 metadata requests total, below the
frozen maximum 128. The no-JS/no-token/no-credentials constraints remain
in force.

## What D4 proves—and does not

The first raised **hooked** method is `VIDEO_EXTRACT` on all controls.
Its nested `INITIAL_EXTRACT`, `PLAYER_RESPONSES` and `FORMAT_SELECTION`
methods returned normally. Therefore the terminal extractor exception
occurred **after the return of `_list_formats`, somewhere in the remainder
of `_real_extract`**, or was raised by a non-hooked helper that it called.
This is a substantially narrower localization than D0–D3.

Pinned yt-dlp 2026.10.21 source has a conditional branch immediately
after `_list_formats`: `if not formats:` may eventually call
`raise_no_formats(reason, expected=True)` (approx. upstream lines
4046–4083). That is a **plausible next hypothesis, not a proven outcome**:
D4 did not inspect `formats` or record whether `raise_no_formats`
was called. Other later exceptions remain possible.

HTTP 2xx, JSON MIME, and returned player response functions do **not**
prove that response bodies are semantically adequate or playable formats
exist. D4 does not prove JS/EJS, PO-token, authentication, egress blocking,
provider-wide impossibility or a viable media pipeline.

**Decision remains `NOT_MADE_M3_A2`.** No V1 split A/V qualification,
no complete 18-cell acceptance matrix, no M3-A3/M3-B adoption; licence and
distribution decision #52 remains mandatory.

## Most valuable next predeclared step: D5

Instrument **post-`_list_formats` termination**, without copying response
bodies or raw exception text: (a) boolean whether the returned formats
container is empty; (b) a closed invocation indicator for
`raise_no_formats` and `report_drm`; (c) optional trusted stage markers
around later metadata construction if (a) and (b) do not account for
the observed failure. These require their own approved versioned schema
and falsification tests; the D4 stage trace/ZIP must stay unchanged.

Finally, disarm the consumed D4 one-shot trigger and restore manual-only
workflow in this reviewed cleanup PR, without further provider traffic.
