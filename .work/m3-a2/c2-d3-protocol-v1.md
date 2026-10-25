# M3-A2-C2-D3 — frozen paired reference version comparator

Frozen on **2026-10-25** · research issue #158 (parent #144 / #50).
Status: **implementation / offline falsification; live results NOT_YET_OBSERVED**.

## Independent falsifiable question

Does substituting a newer complete yt-dlp reference package change extraction
completion for the same frozen V0/V2 cases and named WEB_EMBEDDED/TV profiles?
D2 found 16 HTTP 2xx responses, useful MIME families and four nested
ExtractorError type observations, but no metadata completion. D2 cannot
diagnose why. D3 may show a **version-associated difference**; it cannot
isolate a single extractor patch or assign root cause from exception type.

Prior art: [yt-dlp 2026.10.21](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.21)
updates YouTube web_embedded User-Agent and TV client defaults. Thus
**the whole reference package is the treatment**, even though explicit
`player_client=['web_embedded']` / `['tv']` is held fixed. The real
HTTP headers and provider state may vary by version. No claim of controlling
their byte identity is made.

## Predeclared frozen design

Machine-plan source: `.work/m3-a2/c2-d3-plan-v1.json`.

- **A**: yt-dlp `2026.08.19`, release SHA
  `594bd50c2c78ac432f81600d309fdc4e0a92d82c`.
- **B**: yt-dlp `2026.10.21`, release SHA
  `8cdf4fea48ecf4064387375301d7f294ebdb17c1`.
- Exactly **4 original paired cells** (V0/V2 × WEB_EMBEDDED/TV),
  each observed once under A and once under B, **8 attempts**:
  `V0/web: A,B`; `V2/web: B,A`; `V0/tv: B,A`; `V2/tv: A,B`.
  Balancing first/second exposure limits the most obvious order bias but
  does not eliminate time-dependent changes. No adaptive resampling.
- Exact C1/D0/D1/D2 frozen identity, registry, case selection, fingerprints,
  client names, metadata-only parameter set and source file are inherited.
- Exactly one GitHub-hosted Ubuntu runner and exact Git commit; two isolated
  Python virtual environments with pinned release versions and SHAs.
  The experiment driver invokes a separate Python process for each of the
  eight observations (no module cross-contamination), without passing raw
  video IDs on the command line.
- Hard bounded by D2 `DiagnosticCapture.next_request()` **before every
  network operation**: <=32 metadata attempts per observation, <=256
  overall (typical prior measurement 4 per observation); **0 media probes**.
  Any exception terminates the slice, no automatic network retries/requeue.

Explicitly disabled: JS runtime, EJS, remote components, token provider,
cookies, authentication, provider secrets, proxy/egress switching, bypass
tools, content download, unbounded fallback, public release claims.
Even if the new oracle completes extraction, this is **not** approval to
ship yt-dlp in an Android application; policy #52 remains unsatisfied.

## Artifacts and falsification

Producer: `scripts/measurement/m3_c2_d3.py`.
Offline falsification: `scripts/measurement/tests/test_m3_c2_d3.py`.
Canonical workflow: `.github/workflows/m3-a2-c2-d3.yml`.

Portable bundle: 8 ordinal+case+lane+arm JSON envelopes with versioned
nested D2 typed observations, `summary-v1.json`, `manifest-v1.json`.
The summary is derived from records; no free-form exception text, HTTP URL,
header, IP, content ID or response body is retained. A closed-field audit,
all three original IDs redaction check, profile/source/runner/oracle binding,
SHA-256 manifest and exact 8-cell order are verified before upload.

Reject forgery: changed treatment/version/SHA, altered mode/client, V1
substitution, extra/reordered probe, metadata budget inflation, forged
viability/JS claim, mixed runner/head, altered or missing ZIP entries,
modified summary (even with recalculated digest), case identity before
network I/O. Linux + Windows Verify run **without provider traffic**.

## Exit and limitations

Canonical exact-main GitHub Actions artifact and independently audited
SHA-256 ZIP/entry digests retained in `.work/evidence/` through a
separate reviewed cleanup PR. The workflow is armed by a **single-use
main-branch marker only** and restored to manual dispatch after audit.

The data answers only **whether the two reference versions differ on
the four selected cases at that network/runner moment**. A shared failure
cannot prove JS/PO-token necessity or provider-wide NO-GO. Even a successful
metadata mapping does not qualify split audio/video, prove media Range
support or complete the 18-cell M3-A2 matrix. Keep
`globalDecision=NOT_MADE_M3_A2` until those separate acceptance gates
are satisfied. Any EJS experiment requires explicit new plan, safety/legal
review (#52) and distinct artifacts.
