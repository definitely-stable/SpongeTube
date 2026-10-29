# M3-A2-C2-D5 — terminal no-formats / DRM discriminator

Frozen **2026-10-29** (research issue #158; parent #144 / policy #52).
Status **implementation, offline falsification and pinned-hook acceptance; provider run pending**.

## Falsifiable question and prior evidence

D4 canonical run #40233961424 recorded four identical 16-event traces.
On V0/V2 × WEB_EMBEDDED/TV (yt-dlp 2026.10.21), nested
`_initial_extract`, `_extract_player_responses`, and `_list_formats`
returned normally. Outer `_real_extract` raised only AFTER
`_list_formats` returned. No metadata completion; 16 HTTP_2XX across 4
controls, no media reads. The pinned upstream code at `_video.py`
approximately lines 4046–4083 has a `if not formats:` branch that may
call `raise_no_formats`, possibly via `report_drm`.
**D4 did not determine that formats were empty or which call raised.**

D5 tests precisely that hypothesis. It does NOT inspect raw formats, title,
status/reason strings, JSON, HTML, URLs, video IDs, network headers or any
exception content.

## Frozen repeat control

Machine contract: `.work/m3-a2/c2-d5-plan-v1.json`.
Pinned oracle: **yt-dlp 2026.10.21**, `RELEASE_GIT_HEAD`
`8cdf4fea48ecf4064387375301d7f294ebdb17c1`.
Four original B controls only, D3/D4 ordinals `2,3,5,8`,
V0/V2 × WEB_EMBEDDED/TV. Same original registry fingerprints, pinned
source of ephemeral video IDs, lane names and source commit. Validate
all three case IDs before any provider traffic. One GitHub-hosted Ubuntu
runner; one isolated venv, same local IP/egress during the four cells.
No adaptive scheduling or retries.

At most **32 metadata attempts per cell, 128 overall** and **zero media
probes**. Preserve `js_runtimes={}`, `remote_components=set()`, no PO
token provider, account, cookies, credentials, proxies or egress changes.
No JS/EJS/challenge solving or anti-abuse bypass.

The observer temporarily attaches wrappers to **only three methods** of
the actual pinned YoutubeIE:

- `_list_formats`: when it returns normally, inspect only
  `type(result) is tuple`, arity 4, and `len(result[2]) == 0`.
  Persist only `EMPTY`, `NONEMPTY` or `UNKNOWN`, and a bounded count.
  Do not retain the value, iterate formats, copy URLs or encode exceptions.
- `raise_no_formats`: record one bounded call count before delegation to
  exactly the inherited `InfoExtractor` implementation.
- `report_drm`: same bounded call count and delegation.

No network instrumentation is introduced beyond existing bounded D3.
No method arguments or exception details are inspected. Hooks are
installed after D4 phase instrumentation and restored in reverse, including
removing method overrides for originally inherited methods. Freeze
class identity as `YoutubeIE` in `yt_dlp.extractor.youtube`
(the pinned upstream `__module__` public alias). Pinned PR-only GitHub
workflow checks all hooks **without contacting YouTube**.

Result is one separate D5 terminal object nested alongside an exact
D4 phase/reference document. Exact closed fields:
`formatPopulation`, `formatObservations`,
`raiseNoFormatsCalls`, `reportDrmCalls`.
All counts <=8 per observation; D4 stage/HTTP/provenance validation and
request budgets remain intact. Disallow unexpected fields and
free-form strings. Derive `summary-v1.json` and SHA-256
`manifest-v1.json` from exactly four ordered documents.

## Interpretive boundaries

If `formatPopulation=EMPTY` and `raiseNoFormatsCalls>=1`, D5 supports
the **observed empty-format termination pathway** on that case, but does
not prove the missing formats were caused by IP restrictions, EJS or tokens.
If `formatPopulation=EMPTY` and `reportDrmCalls=1`, D5 supports the
DRM-reporting branch was entered; it does not infer DRM generally.
`UNKNOWN` is not evidence of EMPTY. If formats are non-empty and
`raiseNoFormatsCalls=0`, a later post-format failure remains to be
localized in a separate study. The observed version/egress/case cannot
be extrapolated to all YouTube traffic.

All outcomes remain `globalDecision=NOT_MADE_M3_A2`; cannot complete
18-cell M3-A2, validate split A/V, ship M3-B or resolve policy #52.

## Acceptance/retention

Full Linux/Windows Verify and PR-only pinned-hook preflight, then
one **exact-main** canonical GitHub-hosted run triggered once by
`.work/m3-a2/c2-d5-run-v1.trigger`. No PR provider I/O. Independently
download original artifact; validate original ZIP SHA-256, all manifest
entries, frozen provenance, no media probes, closed fields, nested D4
stage trace, summary derivation and absence of raw case IDs. Publish a
dated science report + digest ledger and **atomically remove** consumed
push trigger and marker in a separate reviewed cleanup PR. Old D0–D4
plans and evidence remain unchanged.
