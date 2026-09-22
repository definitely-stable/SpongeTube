# M0 → M1 Evidence Handoff

Date: **2026-09-22**
Status: **Canonical design input; M0 remains closed**

## Purpose

Record M0 observations that directly constrain the M1 Persistent Playback Core contract.

This document does not reinterpret M0 as a performance benchmark. M0 emulator timings remain diagnostic.

## Accepted M0 basis

M0 closed with the canonical accepted evidence plus a later final-head confirmation. The final-head acceptance re-ran the same runtime/measurement code after documentation-only changes.

Both accepted matrices completed all nine Direct/Standard-Cache N0/N1/N4 cases.

## Observation 1 — playable required-track coverage predicts outage survival better than raw cache bytes

In accepted N4 evidence, the WARM case substantially reduced network demand and shifted the playback failure boundary by roughly one additional fixture segment horizon.

The shift is consistent with increased common audio/video playable coverage rather than a useful interpretation of raw retained byte count.

M1 consequence:

- central correctness metric becomes required-track \`PlayableCoverage\`;
- current reserve is contiguous \`DurablePlayableReserve\` from the playhead;
- raw cache/storage bytes remain secondary accounting only.

## Observation 2 — partial write visibility must be explicit

In the final M0 N1 COLD case, cache bytes observed before player/cache release were lower than the bytes visible to the immediately following WARM case by **311,296 B**.

The earlier accepted run showed the same class of effect with a different delta.

M1 consequence:

- distinguish receiving/sealed/verified/durable/published states;
- never infer durable playable coverage from "bytes observed at end";
- coverage publication happens only after the storage commit barrier;
- evidence records state transitions rather than one ambiguous cache-size number.

This is M1 measurement debt, not grounds to reopen M0.

## Observation 3 — retry/duplicate ownership is observable correctness debt

Final accepted N4 evidence reported **49,152 B** of duplicate range bytes in affected cases while the origin still returned successful HTTP status responses before client-side timeout behavior terminated playback.

M1 consequence:

- FetchBroker owns one physical fetch per FetchKey;
- duplicate range bytes and request attempts stay explicit;
- outcome taxonomy distinguishes HTTP status from transport timeout/cancellation/range/storage outcomes;
- recovery tests must prove that valid already-published coverage is not refetched.

No arbitrary "acceptable duplicate percentage" is frozen before M1 evidence exists.

## Observation 4 — inherited WARM state is not an experimental treatment

M0 WARM intentionally inherited what the previous COLD case retained. Exact retained coverage therefore varied between accepted executions.

This was valid for M0 semantic verification but unsuitable for M1 effect-size or reserve-boundary comparison.

M1 consequence:

- use deterministic semantic coverage seeds;
- canonical positive seeds target playable time, not MB;
- seed manifest records exact per-track intervals and extent identities;
- negative/holed seeds prove conservative CoverageIndex behavior;
- independent verification runs before playback.

## Observation 5 — standard Media3 cache remains a baseline, not Sponge Core

M0 showed useful warm-cache behavior, including zero-network warm N0 and materially lower N4 network demand, but a sufficiently long N4 still ended in client timeout behavior.

M1 consequence:

- retain Direct/Standard Cache for reference where useful;
- do not promote SimpleCache/CacheDataSource to Sponge source of truth;
- Sponge-owned FetchBroker, durable extent publication and CoverageIndex remain required.

## Observation 6 — N4 must become recovery-capable

M0 N4's purpose was to observe a deterministic 120 s no-progress window and baseline failure behavior.

M1 must instead prove:

\`\`\`text
reserve consumption
-> optional bounded stall when reserve is exhausted
-> origin restoration
-> missing fetch progress
-> durable extent publication
-> playback recovery
\`\`\`

Configured outage time, origin gate state, client no-progress, player stall and recovery markers are separate evidence domains.

## Implementation implication

M1 starts with correctness contracts and evidence instrumentation before adaptive policy.

Required order:

\`\`\`text
M1-A contracts/evidence
  -> M1-B ExtentStore
  -> M1-C CoverageIndex/seeds
  -> M1-D FetchBroker
  -> M1-E PlaybackBridge
  -> M1-F restart/N4 recovery
  -> M1-G canonical acceptance
\`\`\`

