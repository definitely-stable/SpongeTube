# M3-A2-C2 — typed metadata failure diagnostic contract v1

Status: **Phase A implemented, offline only** · 2026-10-14 · issue #158.

## What the negative controls proved

Exact-main C1 run `38537003057`, artifact `11880123093`:
- V0/V2 on WEB_EMBEDDED and TV: **four INCONCLUSIVE results**;
- 16 metadata requests, zero media probes;
- metadata extraction was incomplete in all cases;
- no confirmed HTTP 403/429 observation, no validated JS or PO-token necessity.

This rejects neither anonymous YouTube playback generally nor any one cause of
failure. The original M3-A1 probe (and immutable case/18-cell plans) remains
unchanged and must not acquire speculative recovery logic.

## Scope and invariant

The offline module `scripts/measurement/m3_c2_contracts.py` declares the
**m3-a2-c2-metadata-diagnostic-v1** vocabulary and rejects extra fields.
No provider calls, no new production dependencies or network route changes.

A trusted later diagnostic producer may submit only:
- case class, frozen revision/fingerprint, exact primary client/profile;
- exact Git SHA and sanitized GitHub-hosted runner image;
- immutable yt-dlp reference-oracle version and revision;
- explicitly pinned `jsRuntime=DISABLED`, `remoteComponents=DISABLED`,
  `poTokenProvider=ABSENT`, `credentialsUsed=false`;
- metadata request attempts (0–32) with ordinal-indexed, closed typed
  network-failure observations;
- extraction-completion boolean, terminal failure category;
- constant `DIAGNOSTIC_ONLY_NO_PROVIDER_INFERENCE` decision.

Typed classes: `HTTP_403`, `HTTP_429`, `HTTP_OTHER`,
`CONNECT_OR_DNS`, `TLS`, `TIMEOUT`, `EXTRACTOR_ERROR`,
`UNCLASSIFIED`. `JS_RUNTIME_UNAVAILABLE` is **reserved but forbidden in
v1**, because no authenticated structured oracle signal exists to justify
asserting that requirement. A generic yt-dlp warning, a missing runtime,
unknown exception or unsuccessful media metadata extraction never proves
the cause is a JS challenge or a PO-token requirement.

`classify_exception` inspects known exception *types*, numeric HTTP status and
bounded exception-cause links only. It **never calls str(exc)**, includes raw
message text or carries provider IDs, signed URLs, cookies, authorization
headers, tokens, user identifiers or raw egress IPs in the artifact.
An unknown wrapped exception remains UNCLASSIFIED / EXTRACTOR_ERROR.

## Falsification and acceptance

Test suite: `scripts/measurement/tests/test_m3_c2_contracts.py`.

Tests cover HTTP categories, TLS/DNS/timeout errors, nested exceptions with
secrets, forbidden payload fields, forged JS/token/adoption claims,
unexpected client/reference revisions, duplicate request ordinals and
pre-I/O refusal of request 33.

C2 **Phase A exit** requires exact-head Linux/Windows Verify SUCCESS.
There is **no live-provider outcome** and no automatic JS/EJS installation.

## Next strictly conditional experiment

A subsequent Phase B requires a new versioned, bounded research-only
producer and explicit comparator plan. Compare the original no-JS/no-token
reference with a deliberately pinned external runtime/EJS reference (if
security and license policy #52 allow), keeping V0/V2, runner and request
budgets comparable. The changed component MUST be recorded as a variable.

Reference documentation: yt-dlp/yt-dlp Wiki EJS (external JS runtime and
yt-dlp-ejs scripts), and the PO Token Guide. These show plausible constraints
but do not attribute our current failures to one cause.

No PO-token generation, challenge bypass, credentialed video, IP rotation,
account session or production extractor is authorized. M3-A2 remains OPEN,
and M3-A3/M3-B decisions remain blocked.
