# M3-A2-C2-D3 — canonical paired-version yt-dlp evidence

Date: **2026-10-25 UTC**. Research #158, parent #144 / #50.
Status: **CANONICAL EVIDENCE VERIFIED; VERSION COMPARATOR COMPLETE; M3-A2 INCONCLUSIVE**.

## Exact provenance and independent audit

- Implementation PR [#167](https://github.com/definitely-stable/SpongeTube/pull/167),
  merged main `645469fe7286e7c1d9fa5c14c2b7cb2ab760001a`.
- Initial run [#40214033766](https://github.com/definitely-stable/SpongeTube/actions/runs/40214033766)
  failed **before provider I/O** because `Path.resolve()` dereferenced two distinct
  venv Python symlinks to one system interpreter. No paired observation was
  made or uploaded; no scientific evidence is claimed from that failed run.
- Follow-up fix PR [#168](https://github.com/definitely-stable/SpongeTube/pull/168)
  corrected interpreter-location validation and introduced symlink regression tests.
  Canonical exact-main commit: `5c2b72263d8550d682498a2304fd84566353e254`.
- **Successful** first provider-facing paired run:
  [#40214965133](https://github.com/definitely-stable/SpongeTube/actions/runs/40214965133),
  GitHub-hosted Ubuntu, attempt 1, all steps **SUCCESS**.
- Original downloadable GitHub Actions
  [artifact #12606659103](https://github.com/definitely-stable/SpongeTube/actions/runs/40214965133/artifacts/12606659103),
  `m3-a2-c2-d3-version-pairs`.
- Independent original ZIP SHA-256:
  `595f9dd140867c03adbe9158268b862bdfd974b8891242c627de07bec884a154`, equal to the digest published by GitHub Actions.
- Runner: `ubuntu24@20261023.334.3`.
- A pinned installed yt-dlp `2026.08.19` /`594bd50c2c78ac432f81600d309fdc4e0a92d82c`.
- B pinned installed yt-dlp `2026.10.21` /`8cdf4fea48ecf4064387375301d7f294ebdb17c1`.
  The newer release **tag commit** `6ccd977303412151ee23352c6257dd658ffc1987`
  is not its `RELEASE_GIT_HEAD`. The experiment verifies the installed
  version and embedded release revision, not just the tag.

An **independent** ZIP inspection (without the D3 producer or its verifier)
matched the archive digest, exact 10-member set (8 probes, summary, manifest),
all 9 listed manifest file hashes, all 8 ordered A/B identities and case
fingerprints, one source SHA and runner, both pinned package revisions,
client profiles, frozen no-JS/no-token mode, typed evidence vocabulary,
HTTP/MIME classifications, derived paired summary and attempt budgets.
The original ZIP remains on GitHub; only audit digests and conclusions are
retained in the repo. Machine-readable ledger:
`.work/evidence/m3-a2/c2-d3-canonical-v1.json`.

## Direct observations

| Frozen control | A: 2026.08.19 | B: 2026.10.21 | Completion difference |
| --- | --- | --- | --- |
| V0 / WEB_EMBEDDED | EXTRACT_INFO_RAISED | EXTRACT_INFO_RAISED | No |
| V2 / WEB_EMBEDDED | EXTRACT_INFO_RAISED | EXTRACT_INFO_RAISED | No |
| V0 / TV | EXTRACT_INFO_RAISED | EXTRACT_INFO_RAISED | No |
| V2 / TV | EXTRACT_INFO_RAISED | EXTRACT_INFO_RAISED | No |

Every one of the **8** observations issued **4** counted metadata requests.
All **32** returned `HTTP_2XX`, with ordered MIME families
`HTML, HTML, TEXT_OTHER, JSON` per observation. **0/4 A and 0/4 B**
metadata extractions completed. Every terminal extractor result was an outer
`YTDLP_DOWNLOAD_ERROR` with nested `YTDLP_EXTRACTOR_ERROR` type.
`networkFailures=[]`, base `terminalClass=UNCLASSIFIED`,
**0 media probes**, 0 cookies/tokens, 0 JS/EJS runtime, no credentials.
The observation order was frozen `AB/BA/BA/AB`, under one runner/egress.

## Scientific conclusion and limits

Replacing the pinned **entire reference package** with the newer Oct 21
yt-dlp did **not** change the measured ability to extract metadata on these
four controls. The treatment includes changing extractor internals and the
release's web_embedded User-Agent/TV-client changes; this is **not** evidence
isolating an extractor patch. The two versions share the same runner, egress,
test cases and provider time window, so the failures are strongly correlated.

HTTP success + JSON MIME does not prove that the JSON response contains the
metadata expected by the extractor. A nested `ExtractorError` **type**
cannot establish its cause. The experiment does **not** prove JS/EJS or
PO-tokens necessary, rule out egress-related/provider-state causes, establish
YouTube-wide NO-GO, prove split A/V or authorize production integration.

**Global decision remains `NOT_MADE_M3_A2`.** Original D0/D1/D2, C1 and
18-cell matrix are unchanged; M3-A3 and M3-B remain blocked, including policy #52.

## What to investigate next

A next **separately versioned and approved** D4 should discriminate
provider-response semantic failure from extractor/runtime failure. Candidates:
closed, privacy-safe stage-level classification of trusted extractor paths
(without raw response bodies or exception messages) versus a carefully reviewed,
isolated JS/EJS reference arm. Preserve no-token/no-auth limits and obtain
policy/security approval (#52) before running any JS/EJS comparator.
A new reference experiment must not silently mutate frozen D0–D3 artifacts.

Cleanup PR removes the consumed `.work/m3-a2/c2-d3-run-v1.trigger`
and workflow `push` path *atomically*, restoring manual-only dispatch.
