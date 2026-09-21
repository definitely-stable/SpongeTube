# AGENTS.md

This file is a short execution contract. Canonical project documentation lives in `.work/`.

## Authority

Read, in order:

1. `.work/PRODUCT.md`
2. `.work/ARCHITECTURE.md`
3. `.work/GOVERNANCE.md`
4. `.work/VERIFICATION.md`
5. `.work/ROADMAP.md`

Read the relevant ADR/evidence records for the subsystem being changed.

## Scope guard

Current product scope is Android phone/tablet, YouTube-oriented long-form VOD.

Do not add Shorts, Live, TV, iOS, torrent support or generic plugin-runtime work unless `.work` explicitly changes first.

## Change rules

- Never push directly to `main`.
- Use a short-lived branch and PR.
- Keep one logical change per PR.
- Do not change unrelated areas "while here".
- Do not silently change architectural invariants.
- Do not weaken/remove tests merely to make CI pass.
- Do not claim performance/resource improvement without reproducible evidence.
- Do not fabricate benchmark/test results.
- Do not introduce secrets, credentials or private user data.
- Follow the repository's Conventional Commit/PR-title rules.

## Verification

Run the repository-defined checks relevant to the change. If a required check cannot be run, state that explicitly in the PR; never imply verification that did not occur.

For performance/network/storage claims, follow `.work/VERIFICATION.md` and add evidence under `.work/evidence/`.

## Architecture boundaries

Sponge Core owns remote media fetches. Playback/prefetch/offline retention must not independently refetch the same media coverage.

Respect the user's system-default network/VPN policy. Never add hidden route bypass behavior.


## Current build verification

For M0-A Android build changes, run:

```text
./gradlew check assembleDebug
```

Configuration-cache compatibility is required for this verification path.
