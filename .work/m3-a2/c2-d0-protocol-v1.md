# M3-A2-C2-D0 — typed no-JS metadata instrumentation

Owner: [issue #158](https://github.com/definitely-stable/SpongeTube/issues/158).
Protocol ID: `m3-a2-c2-d0-nojs-2026-10-14-v1`.
State: **Frozen implementation; live observations not yet accepted**.

## Why this experiment is necessary

C1's canonical main-branch [run #38537003057](https://github.com/definitely-stable/SpongeTube/actions/runs/38537003057)
yielded **4/4 INCONCLUSIVE**, only 16 metadata requests and zero readable-media probes.
The original pinned yt-dlp reference runs with `js_runtimes={}` and `remote_components=set()`.
All the control failures could share a reference-oracle, runtime, provider,
network or fixture explanation. No cause is established.

There is also an **instrumentation confound**: pinned yt-dlp uses its own
`yt_dlp.networking.exceptions.HTTPError` type. A logger/string-based observer
or one that recognizes only Python `urllib.error.HTTPError` can fail to classify
an actual typed response. In D0 the wrapper inspects only pinned exception types
and numeric statuses at the network I/O boundary. It does **not** inspect
exception messages, infer a missing JS runtime or adopt any token functionality.

## Exact frozen D0 plan

`.work/m3-a2/c2-d0-plan-v1.json` inherits only the following four **existing**
C1 cells in order, on the exact same frozen registry:

1. V0 / WEB_EMBEDDED
2. V2 / WEB_EMBEDDED
3. V0 / TV
4. V2 / TV

The same yt-dlp 2026.08.19 package and pinned RELEASE_GIT_HEAD apply.
Maximum 32 metadata requests per cell / 128 in total, counted before network
I/O. There are **zero** media probes, zero hidden V1 requests, no retries
added by this module, no JS/EJS, cookies, auth, PO-token provider, IP rotation,
VPN bypass or provider traffic camouflage. The original research producer,
C1 archive, M3-A1 contract and 18-cell M3-A2 plan are untouched.

The only deliberate difference from C1 is a **metadata-only, typed diagnostic
observer**, so a D0 observation cannot be claimed byte-identical to C1.

Each standalone diagnostic must validate
`m3-a2-c2-metadata-diagnostic-v1` and retain only case fingerprints,
client/profile, pinned oracle, request count/ordinal, closed network failure
categories, extraction-completion boolean and an UNKNOWN-safe terminal class.
The summary has constant `globalDecision=NOT_MADE_M3_A2`.
A captured `HTTP_403` indicates a response to a particular request in this
specific context, **not** a provider-wide rejection; a terminal
`UNCLASSIFIED` is not evidence that JS/EJS or PO tokens are required.

## CI/provenance and stop conditions

The one-shot exact-main trigger is
`.work/m3-a2/c2-d0-run-v1.trigger`.
Workflow: `.github/workflows/m3-a2-c2-d0.yml`, GitHub-hosted Ubuntu 24.04.
The only other allowed entry is explicit `workflow_dispatch`.

The workflow preflights all three raw source fingerprints before provider
access, installs pinned yt-dlp in an isolated venv, runs four exact cells,
independently validates all four artifacts and their manifest, and scans all
uploaded files for raw IDs. It uploads only after successful checks, then
deletes the ephemeral pinned source. Failure details are suppressed rather
than logging private exception text.

After canonical run, audit exact SHA/runner, ZIP digest and every file digest.
Retain a **dated evidence conclusion**, remove the consumed trigger **and**
remove the workflow push event in the same commit to avoid a deletion-triggered
second live experiment. If the run is inconclusive, record that result without
retrying blindly.

## Next phase boundary

A runtime/EJS comparison must be defined in its **own** pinned protocol with
an explicit single independent variable, supply-chain/version/license review,
same control cases, and separate CI evidence. It is neither part of D0 nor
permission to modify production Sponge Core.

References: yt-dlp's EJS setup guidance documents external JavaScript
runtimes and the version-matched yt-dlp-ejs companion; this makes missing
runtime a **hypothesis**, not a proven cause.
Production adoption remains barred until #52 is resolved.
