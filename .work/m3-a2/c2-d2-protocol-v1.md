# M3-A2-C2-D2 — trusted MIME and nested-exception diagnostic

Date: 2026-10-25. Status: **implementation pending exact-head PR CI and canonical main evidence**.
Owner: #158 (parent #144 / #50). Decision remains `NOT_MADE_M3_A2`.

## Scientific question

D1 observed 16/16 returned HTTP 2xx response objects and four yt-dlp
`DownloadError` wrappers without a completed extraction. Its Content-Type
classification was ineffective: pinned `Response.headers` is
`email.message.Message`, whereas D1 required `collections.abc.Mapping`.
D1 therefore retained 16 `UNKNOWN` MIME observations. It also did not
inspect typed nested causes. Both observations are instrumentation limits,
not evidence of a JS, token, egress or provider requirement.

D2 is a **new experimental version**, not a correction of the immutable
D0/D1 source, outcomes or archived artifacts.

## Frozen controls and execution

Source of truth: `.work/m3-a2/c2-d2-plan-v1.json`. It is an exact extension
of the D1 plan and preserves these ordered cells:

1. V0 / WEB_EMBEDDED
2. V2 / WEB_EMBEDDED
3. V0 / TV
4. V2 / TV

The exact case registry, SHA-256 case fingerprints, pinned yt-dlp
`2026.08.19` / `594bd50c2c78ac432f81600d309fdc4e0a92d82c`,
client profiles, and same Git SHA/runner requirement remain unchanged.

The maximum is **32 metadata request attempts per cell, 128 total**.
A request is charged before I/O. There are **zero media reads**.
Only an explicit research workflow run on GitHub-hosted Ubuntu may contact
the provider; normal PR verification is synthetic/offline.

`js_runtimes={}`, `remote_components=set()`, no PO-token provider, login,
cookies, credentials, retry bypass, route manipulation, synthetic impersonation,
full download or production provider dependency. No production engine changes.

## Additional versioned D2 observations

D2 retains the D1 closed response event vocabulary but changes observation:

- MIME family is read **only** from a recognized yt-dlp
  `networking.common.Response` whose `headers` is
  `email.message.Message`. A single Content-Type header is reduced
  immediately to `JSON|HTML|MEDIA|TEXT_OTHER|OTHER|UNKNOWN`.
  Missing, duplicate, unsupported or failing access yields `UNKNOWN`.
  Content-Type classification never proves response-body validity.
- The terminal `YTDLP_DOWNLOAD_ERROR` wrapper remains visible separately
  from a **typed nested cause**. D2 walks at most eight nodes through
  Python `__cause__` / `__context__` and pinned yt-dlp
  `DownloadError/ExtractorError.exc_info[1]`, plus the known
  `ExtractorError.cause` attribute. Cyclic/unknown inputs stop safely.
- Nested cause is exactly one of `NONE|UNKNOWN|YTDLP_EXTRACTOR_ERROR|
  HTTP_403|HTTP_429|HTTP_OTHER|TLS|CONNECT_OR_DNS|TIMEOUT|OTHER_NETWORK`.
  Even a confirmed typed `ExtractorError` is **not** a claim of JS/EJS
  necessity, token gating, provider unreachability or global no-go.
- D2 is independent of the original `m3-a-probe-v1` artifacts and can
  never be fed into the 18-cell compatibility aggregator as media evidence.

No response body, raw header, status-reason phrase, original exception text,
URLs, IPs, provider content IDs, authentication/session/token material,
traceback, user identifier or cookies is serialized. Typed diagnostics only.
The bundle is scanned for all three raw in-memory case IDs before upload.
Known secrets in error messages are never processed.

## Falsification and independent audit

Implementation: `scripts/measurement/m3_c2_d2.py`.
Tests: `scripts/measurement/tests/test_m3_c2_d2.py`.
Research workflow: `.github/workflows/m3-a2-c2-d2.yml`.

- `validate-plan` checks exact inheritance, four ordered cases, 32/128
  budgets, frozen mode and oracle before any network access.
- `run` produces four redacted case JSON files plus a derived
  `summary-v1.json` and `manifest-v1.json`.
- `verify` checks exact case/profile/fingerprint bindings, source SHA,
  runner identity, per-file SHA-256, request counts, MIME and exception
  enumerations, forbidden fields, no media reads and global decision.
- Synthetic negative tests reject spoofed HTTP responses, extra/duplicate
  MIME header, forged nested cause, cyclic exceptions, case/protocol drift,
  forged JS/adoption claims, excess request attempts and artifact tampering.
- Exact-head Verify on Linux and Windows is required prior to merge. The
  live provider run is NOT a PR/release acceptance gate.

## Canonical evidence procedure

The reviewed implementation PR carries the **single-use**
`.work/m3-a2/c2-d2-run-v1.trigger` and a path-scoped `main` push event.
No provider calls occur on PR/Verify; squash merge on main triggers one
canonical run. An explicit reviewed manual research run remains available.
The run must
use one exact GITHUB_SHA, one GitHub-hosted Ubuntu runner and the pinned
reference. Independently download the original ZIP; record its SHA-256 and
each entry digest under `.work/evidence/`. Preserve the original artifact
without retroactive correction. If one-shot automation was used, remove both
its marker and push event atomically in a cleanup PR.

**Exit**: four verified typed observations, independent hash/provenance
audit, dated interpretation, CI checks passing. D2 may legitimately be
`INCONCLUSIVE`; it does not itself qualify V1 or authorize M3-A3/M3-B.
A newer yt-dlp or EJS comparator requires a separate versioned plan and
the licensing/distribution review in #52.
