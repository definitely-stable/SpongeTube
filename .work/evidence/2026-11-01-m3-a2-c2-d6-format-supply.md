# M3-A2-C2-D6 — canonical format-supply provenance

Executed: **2026-11-01 07:41–07:42 UTC**. Issue #158 (parents #144/#50).
Status: **CANONICAL ZIP INDEPENDENTLY VERIFIED; D6 COMPLETE.**
Global research decision: **NOT_MADE_M3_A2**.

## Canonical execution and reproducibility

- Implementation [PR #176](https://github.com/definitely-stable/SpongeTube/pull/176), merged at `3f7af97ef62eb6dcc7e4b29545163be659def060` after exact-head Linux/Windows Verify and pinned-hook preflight SUCCESS.
- One-shot exact-main [PR #177](https://github.com/definitely-stable/SpongeTube/pull/177), merged at **`0cb6d82dc3f45c5e63f511527ca697522da21d06`** after Linux/Windows Verify SUCCESS.
- GitHub-hosted canonical [run #41285812385](https://github.com/definitely-stable/SpongeTube/actions/runs/41285812385): **SUCCESS**, event `push`, exact head `0cb6d82dc3f45c5e63f511527ca697522da21d06`.
- Original GitHub [artifact #13075800552](https://github.com/definitely-stable/SpongeTube/actions/runs/41285812385/artifacts/13075800552), `m3-a2-c2-d6-format-supply`, 6,884 ZIP bytes. GitHub checksum = independent original ZIP checksum: `2bab18a9563200f7ebe07c7fa2ca4b0e8699b44a674401122b73945bfb0fc5eb`.
- Original ZIP manifest SHA-256: `8fbf7428e69eb46a98c281a40888a56746448baf677d892c7b896e4188f90362`. Five manifest member SHA-256 values archived in the ledger `.work/evidence/m3-a2/c2-d6-canonical-v1.json`; original ZIP remains in GitHub Actions.
- Exact runner `ubuntu24@20261026.354.1`; pinned `yt-dlp 2026.10.21` with `RELEASE_GIT_HEAD=8cdf4fea48ecf4064387375301d7f294ebdb17c1`.

## Independent original-ZIP audit (not the experiment producer)

Downloaded the original Actions ZIP and independently computed SHA-256. Parsed its six-entry set; validated the complete five-entry manifest plus SHA-256 for each member; checked all nested D3 oracle/runner/exact case fingerprint/client profile and 32-request control budgets, typed HTTP response events, D4's sixteen ordered method-stage events, D5 terminal calls, D6 closed shape vocabulary and counters. Recalculated the four observation rows and summary from nested records, including the unknown-safe `selectedInputCandidateState`. Checked privacy-closed artifact fields and no embedded URLs/raw case IDs. All PASS.

## Observations (four fixed B controls)

| Control | API return `streamingData` | Selected input `formats` / `adaptiveFormats` | Emitted | PO skip reports | Metadata |
| --- | --- | --- | ---: | ---: | ---: |
| V0 / WEB_EMBEDDED | ABSENT | ABSENT / ABSENT | 0 | 0 | 4 |
| V2 / WEB_EMBEDDED | ABSENT | ABSENT / ABSENT | 0 | 0 | 4 |
| V0 / TV | ABSENT | ABSENT / ABSENT | 0 | 0 | 4 |
| V2 / TV | ABSENT | ABSENT / ABSENT | 0 | 0 | 4 |

Each control had exactly **one** `_extract_player_response` return snapshot (`streamingData=ABSENT`), then two player-response shapes passed to format selection: first `streamingData=NULL` and second `streamingData=ONE`, but **both lack `formats` and `adaptiveFormats` lists**. The second `streamingData` dictionary may be synthetic: pinned yt-dlp calls `setdefault` during normalization. D6 does not capture original wire JSON or the embedded initial response before yt-dlp strips its formats.

All four controls: `NO_CANDIDATES_IN_SELECTED_INPUT`, `emittedFormatMappings=0`, `poTokenSkipReportCalls=0`; D5 `formatPopulation=EMPTY`, `raiseNoFormatsCalls=1`, `reportDrmCalls=0`; D4 sixteen-event trace; 4 HTTP_2XX metadata requests per control. **Total 16 metadata requests, 0 media reads, 0 complete media extractions.**

## Scientific interpretation and limitations

D6 supports the **narrow, important observation** that the chosen yt-dlp format-selection input contained no normal `formats/adaptiveFormats` candidates for the four frozen controls. There is no observed stage in which regular source candidate lists were populated then dropped by the checked PO-token skip-report path. Thus blindly implementing a PO token generator or JS/EJS without further evidence is not warranted.

**Do not interpret this as proof that the network source supplied no video formats.** `_extract_player_response` returns extractor-processed data, not raw server bytes, and embedded initial player responses are unobserved before deliberate format stripping. HLS/DASH manifest keys, format URLs/signature cipher or additional playability semantics were not measured by D6. A zero skip-report counter cannot prove that no token policy could apply. Four 2xx requests per control do not establish playback compatibility.

### Next narrowly scoped research gate (no broad speculative experiment)

Follow up only with a **bounded D7 source-structural discriminator** for *independent manifest presence* (`hlsManifestUrl`, `dashManifestUrl`) and original embedded player-response provenance/normalization, with closed enums and no raw values. Preserve all D6 controls, oracle, requests, runner mode, zero media and privacy restrictions. Prefer a source-verified offline structural preflight before another live run. If no new falsifiable distinction is available, stop and record the remaining uncertainty rather than extending instrumentation indefinitely.

M3-A3/M3-B still blocked. #52 remains an independent production policy gate. The one-shot marker and its `push` trigger are removed atomically in this evidence-closure PR; the D6 workflow returns to **manual-only**.
