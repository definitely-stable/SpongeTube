# M3-A2-C2-D7 — parsed embedded and manifest provenance

Status: **frozen structural research proposal; no live D7 evidence**.
Owner: #158. Derived from independent canonical D6 run #41285812385.

## Falsifiable question

D6 saw no ordinary format-list candidates at the pinned yt-dlp input to
\`_list_formats\`. It did **not** see the unmodified parsed embedded initial
player object, nor did it check \`hlsManifestUrl\`/\`dashManifestUrl\`.

D7 observes three separate identity-qualified stages:
1. Return value of inherited \`_search_json\` only for the exact
   \`_YT_INITIAL_PLAYER_RESPONSE_RE\` pattern in pinned
   \`YoutubeIE._extract_player_responses\`. This is the *parsed embedded
   initial object*, not wire JSON; zero observations is UNOBSERVED.
2. Return value of pinned \`_extract_player_response\` before its caller
   adds synthesized \`streamingData\` metadata.
3. Normalized responses returned by \`_extract_player_responses\`, and the
   actual fourth bound positional argument to \`_list_formats\`.

D7 retains exact nested D6→D5→D4→D3 data, including its candidate-count
buckets. It records *only* closed flags: \`ABSENT\`, \`NULL\`, \`EMPTY\`,
\`STRING_PRESENT\`, \`OTHER\`, \`UNKNOWN\` for each manifest key. No URL,
manifest body, response text, status reason, identity or token is retained.
Every D7 candidate shape must equal its D6 nested shape at the same stage.
Source embedded observation is NOT treated as equivalent to the later
normalized response; the pinned implementation deliberately writes
\`streamingData=None\` on a shallow copied initial response, and may synthesize
\`streamingData={}\` plus internal non-serializable metadata on API response.

## Frozen controls and limits

The same four B controls as D3–D6: V0/V2 × WEB_EMBEDDED/TV,
ordinals 2/3/5/8. Exact pinned yt-dlp 2026.10.21,
RELEASE_GIT_HEAD 8cdf4fea48ecf4064387375301d7f294ebdb17c1.
Up to 32 metadata requests/cell, 128 total, **zero media probes**.
No JS/EJS, remote components, PO tokens, accounts, proxies, routing
changes or direct network access other than the existing metadata oracle.
Each control is an isolated child; all diagnostic hooks restore in finally.
Any hook drift, malformed shape, privacy leak, budget overrun, plan drift or
SHA mismatch fails closed. Portable evidence: exact four nested records,
summary and SHA-256 manifest (six members). Only manual dispatch is defined
for the provider-facing D7 workflow; PR preflight is offline.

## Interpretation gates

- Embedded \`STRING_PRESENT\` + selected \`ABSENT\` supports that *a parsed
  embedded manifest reference existed earlier*, not that it was playable.
- API manifest presence and selected absence supports a normalization
  disappearance hypothesis only when source identity can be matched.
- All absent/unknown values do NOT prove that YouTube's network wire had
  no formats; embedded source may be unobserved. D7 does not parse manifests.
- If a manifest is present at selected input despite zero ordinary lists,
  the D6 result alone cannot justify dismissing manifest format paths.
- Never infer that PO tokens, JS/EJS, DRM, or production adapter adoption
  are necessary from shape-only flags. M3-A2 remains NOT_MADE_M3_A2.
- If D7 cannot distinguish any new branch, stop and record uncertainty rather
  than adding broader speculative experiments.
