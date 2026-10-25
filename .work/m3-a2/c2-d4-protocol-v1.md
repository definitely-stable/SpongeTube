# M3-A2-C2-D4 — typed YoutubeIE execution-stage trace

Frozen 2026-10-26 UTC; issue #158, parents #144/#50.
Status: **implementation / offline acceptance, canonical observation pending**.

## Question

D3's 8 paired observations returned HTTP 2xx / MIME
`HTML → HTML → TEXT_OTHER → JSON` without any completed metadata mapping
in both pinned reference versions. Both nested error classes were
`YTDLP_EXTRACTOR_ERROR`. This does **not** establish which extractor
function fails or whether the response body is semantically valid.

D4 asks: **at which pinned, trusted YoutubeIE method did extraction stop?**
Only method ENTER/RETURN/RAISE is recorded, never arguments, return values,
exceptions, URL, response body, JSON keys, headers, cookies or strings.

## Exact plan, read-only ancestry

Canonical plan: `.work/m3-a2/c2-d4-plan-v1.json`.
Independent producer: `scripts/measurement/m3_c2_d4.py`.

- Preserve original C1/D0/D1/D2 and paired D3 files/ledger verbatim.
- **Only B**, yt-dlp `2026.10.21`,
  `RELEASE_GIT_HEAD=8cdf4fea48ecf4064387375301d7f294ebdb17c1`.
  Earlier D3 found no version-associated change in metadata completion.
- Exactly four D3 B steps: ordinal `2,3,5,8`, corresponding to
  V0/V2 × WEB_EMBEDDED/TV. The same three raw case IDs are validated
  against their frozen SHA-256 hashes **before** provider access.
- 32 metadata request attempts/cell, 128 aggregate; 0 media requests.
- Strict source commit and runner provenance, GitHub-hosted Ubuntu,
  isolated pinned package environment, no modified upstream package.
- JS/EJS runtime, remote components, PO-token provider, account/cookies,
  proxy/egress switching and media/download are forbidden. Do not bypass
  access controls or suppress evidence of provider limitations.

### Eight pinned method hooks

| Trusted method | Closed label |
| --- | --- |
| `_real_extract` | VIDEO_EXTRACT |
| `_initial_extract` | INITIAL_EXTRACT |
| `_download_initial_webpage` | WEBPAGE_FETCH |
| `_download_initial_data` | INITIAL_DATA |
| `_get_requested_clients` | CLIENT_SELECTION |
| `_extract_player_responses` | PLAYER_RESPONSES |
| `_extract_player_response` | PLAYER_RESPONSE |
| `_list_formats` | FORMAT_SELECTION |

Names/signatures are checked against the pinned `YoutubeIE` at runtime.
Instrumentation temporarily wraps class methods and restores exact original
function objects via `finally`. Each wrapper immediately delegates to
the original call without changing inputs, outputs, retry behavior, request
order or exceptions; only closed `ENTER/RETURN/RAISE` markers are retained.
One phase trace <=96 events. Stack matching enforces balanced nesting,
monotonic ordinals and agreement with terminal extraction state. A failed
observer produces NO canonical provider result.

**Scientific limit:** the earliest raised hooked method is the **deepest
observed failing stage**, not a proven reason within that method. Unhooked
helpers and upstream body semantics are not visible. An error at
`PLAYER_RESPONSES` might result from missing, invalid or unexpectedly
structured JSON, provider treatment, configuration or other causes.
No runtime/token necessity can be inferred.

## Evidence/acceptance contract

Each of four artifacts embeds the exact previously versioned D3 reference
envelope and a new D4 closed stageTrace. Validate the inner reference
independently against D3 (case fingerprint, pinned version, runner, SHA,
method-agnostic HTTP/error records) and D4 hooks separately. Summary is
derived, with `firstRaisedPhase` or NONE; `globalDecision=NOT_MADE_M3_A2`
always. SHA-256 manifest covers all four files and summary. No free-form
data is printable on failure or included in portable evidence.

Negative tests reject missing hooks, spoofed stage, extra privacy fields,
unmatched EXIT, duplicate ordinals, event ceiling, forged success/JS,
version drift, mismatched case fingerprint and corrupted artifacts even
with recomputed file SHA. Provider I/O is never executed in PR Verify.

After reviewed squash merge, a single GitHub-hosted exact-main run will be
triggered ONLY by `.work/m3-a2/c2-d4-run-v1.trigger`. Retain the ZIP
and independently verify hashes, case/runner/source bindings and stage
sequence before publishing a dated scientific report and atomically removing
the one-shot marker and the workflow `push` event. Steady state remains
manual `workflow_dispatch`.

D4 is not production adoption; original M3-A2/M3-A3/M3-B remain open and
the license/security/distribution gate #52 still applies. Any experiment
enabling JS/EJS requires a *separate* approved contract, not a D4 edit.
