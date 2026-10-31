# M3-A2-C2-D6 — bounded structural format-supply discriminator

Status: **predeclared implementation / offline tests; no canonical provider evidence**.
Owner: #158, parent #144 / #50; production policy #52 remains open.
Frozen: 2026-11-01. Evidence does not supersede D0–D5.

## Question and falsification boundary

D5 proved that four fixed V0/V2 × WEB_EMBEDDED/TV controls exited via
\`_list_formats\` EMPTY then \`raise_no_formats\`, not *why*. D6 asks whether
the pinned yt-dlp path received observable stream-list structures and whether
the original extractor emitted format mappings. A difference alone does not
prove that tokens, challenges, egress, DRM or any one skip policy caused it.

The pinned upstream \`_extract_player_responses\` deliberately removes
\`streamingData\` from a separately copied embedded response, and uses
\`setdefault('streamingData', {})\` on accepted responses. Therefore presence
in a POST_NORMALIZATION response is **never called source evidence**.
The \`_extract_player_response\` hook records only a PRE_NORMALIZATION
*reference-extractor return*, not raw server JSON, and misses embedded-only
initial responses. Source absence cannot be established from an empty pre
list. \`_extract_formats_and_subtitles\` is a generator; the observer wraps
its natural iteration with no lookahead or second consumer.

## Frozen controls and limits

Exactly four B arms, in original D3 ordinals 2/3/5/8:
V0/WEB_EMBEDDED, V2/WEB_EMBEDDED, V0/TV, V2/TV.
yt-dlp 2026.10.21, installed RELEASE_GIT_HEAD
\`8cdf4fea48ecf4064387375301d7f294ebdb17c1\`.
The three raw case IDs must match frozen SHA-256 registry fingerprints before
any provider interaction. One exact Git SHA, one GitHub-hosted Ubuntu runner,
up to 32 metadata attempts per arm/128 total; **zero media probes**.
No JS/EJS, remote components, tokens, cookies, account, IP rotation, traffic
camouflage, additional retries, route bypass or production integration.

Only five exact \`YoutubeIE\` hooks are added, nested under existing D4 stage
and D5 terminal hooks. They restore the original functions even on failure.
Persistent D6 document includes the exact validated nested D5→D4→D3 record
plus the following exclusively typed/bounded observations:

- \`preNormalizationApiResponses\`: shapes returned from
  \`_extract_player_response\` before its caller normalizes them (0–8).
- \`postNormalizationResponses\`: shapes returned by
  \`_extract_player_responses\` (0–8), potentially synthetic.
- \`selectedInputResponses\`: shapes passed into \`_list_formats\` (0–8).
- \`selectionCalls\` (0–1), \`emittedFormatMappings\` (0–256) and
  \`poTokenSkipReportCalls\` (0–32).

Shape fields are only \`streamingData\`, \`formats\`, \`adaptiveFormats\` with
closed buckets ABSENT/NULL/EMPTY/ONE/TWO_TO_EIGHT/NINE_TO_64/OVER_64/UNKNOWN.
\`streamingData=ONE\` means a nonempty **dictionary**, not a count of source
streams. \`emittedFormatMappings\` counts generator-yielded dicts carrying
\`format_id\`; it does not enumerate formats filtered before the yield.
\`poTokenSkipReportCalls\` counts a trusted reporting function call, not
the number of skipped formats. Unknown/unobserved must not be coerced into
ABSENT, REQUIRED, JS_REQUIRED or NO_GO.

The summary uses `selectedInputCandidateState=PRESENT`,
`NO_CANDIDATES_IN_SELECTED_INPUT` or `UNKNOWN`, never a boolean that conflates
missing observations and observed absence. `preApiResponseObservation=UNOBSERVED`
when no API-return hook fired, and does not claim wire-response absence.

No raw response, title, player status text, provider ID, URL, header, exception,
credential, token, IP or source format entry may be serialized. All outbound
observations are closed primitive types and validated before persistence.
No arbitrary provider objects are stringified or iterated.

## Acceptance protocol

1. Offline plan and synthetic falsification tests, Linux + Windows Verify.
2. A separate pinned yt-dlp hook preflight on PR (no provider I/O).
3. Only after reviewed/merged code, one explicitly authorized manual
   \`workflow_dispatch\` research operation on exact main.
4. A separate independent inspection of the original Actions ZIP SHA-256,
   manifest entries, nested D3/D4/D5 records, 4 fixed identities, budgets,
   shape vocabulary, derived summary and redaction.
5. Retain dated report and content-addressed ledger under \`.work/evidence/\`.
   Restore/keep manual-only execution; no provider calls on PR or merge.
6. Return \`NOT_MADE_M3_A2\` even if the diagnostic succeeds. A new reviewed
   experiment is necessary to test any hypothesized JS/PO/SABR requirement.

Stopping conditions: hook/source drift, provenance mismatch, unknown fields,
unbounded counts, child failure, privacy leak or conflicting D5 telemetry
fail closed. No live response is a deterministic release/PR acceptance gate.
