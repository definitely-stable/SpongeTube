# SpongeTube Repository Governance v0.1

Status: **Normative**
Date: **2026-09-21**

This document defines how changes enter `main`. It applies to humans, coding agents and automation.

## 1. Repository model

SpongeTube uses a lightweight trunk-based workflow:

```text
short-lived branch
      ↓
draft PR when useful
      ↓
implementation + evidence
      ↓
ready for review
      ↓
required checks
      ↓
squash merge
      ↓
main
```

There is no long-lived `develop` branch.

`main` must remain releasable and must not be used as a scratch branch.

Direct pushes to `main`, force-pushes to `main` and deletion of `main` are prohibited in normal operation.

## 2. Branches

Branches are short-lived and represent one coherent change.

Recommended forms:

```text
feat/<issue>-<slug>
fix/<issue>-<slug>
perf/<issue>-<slug>
refactor/<issue>-<slug>
test/<issue>-<slug>
docs/<slug>
chore/<slug>
spike/<issue>-<slug>
hotfix/<issue>-<slug>
```

Examples:

```text
feat/42-playable-coverage
fix/57-singleflight-cancel
perf/63-cronet-benchmark
docs/architecture-v0.2
spike/71-youtube-sabr
```

Rules:

- use lowercase kebab-case;
- include the issue number when the change is issue-driven;
- do not reuse a merged branch for unrelated work;
- delete merged branches;
- avoid stacked PRs unless the split materially improves reviewability;
- if stacked PRs are used, each PR must still be independently understandable and explicitly state its dependency.

## 3. Issues and planning

An issue is required for:

- milestone implementation work;
- user-visible behavior changes;
- bugs;
- performance work;
- provider/network compatibility work;
- architecture changes;
- security-sensitive changes.

An issue is optional for:

- trivial documentation corrections;
- mechanical repository chores;
- typo-only changes.

Do not expand a PR to absorb unrelated review feedback. Record out-of-scope findings as a new issue and link them.

## 4. Commits

SpongeTube follows Conventional Commits 1.0.0:

```text
<type>[optional scope]: <description>
```

Allowed primary types:

- `feat` — user-visible or domain capability;
- `fix` — defect correction;
- `perf` — measured performance/resource improvement;
- `refactor` — structural change with no intended behavior change;
- `test` — tests, fixtures or verification harness;
- `docs` — documentation only;
- `build` — build system/dependencies;
- `ci` — CI/CD workflows;
- `chore` — repository maintenance not covered above;
- `revert` — revert of an earlier change.

Recommended scopes:

```text
core
coverage
fetch
scheduler
network
transport
youtube
playback
storage
offline
ui
benchmark
build
repo
docs
```

Examples:

```text
feat(core): add playable coverage intersection
fix(fetch): join in-flight range after seek
perf(storage): reduce extent index lookups
test(network): add deterministic blackout profile
docs(architecture): define request budget
ci(repo): add Android verification workflow
```

Commit rules:

- description is concise, imperative and in English;
- one commit should represent one coherent intent;
- do not mix unrelated refactors with feature/fix work;
- non-obvious changes should explain **why** in the commit body;
- breaking changes use `!` and/or a `BREAKING CHANGE:` footer;
- references such as `Refs: #123` or `Fixes: #123` may be placed in the footer;
- secrets, tokens, private URLs and personal data must never be committed;
- generated artifacts are committed only when the repository explicitly treats them as source-of-truth.

During a draft PR, `fixup!`/temporary commits are acceptable for local collaboration. They must not define the history of `main`; the PR is squash-merged.

Prefer branch commits that build and test independently when practical. Do not create artificial commit fragmentation merely to satisfy this preference.

## 5. Pull request title

Every PR title must be a valid Conventional Commit summary:

```text
feat(core): add single-flight fetch broker
fix(network): preserve reserve across route switch
docs(repo): establish contribution policy
```

The PR title becomes the canonical squash commit title on `main`.

Do not use titles such as:

```text
Update stuff
WIP
Changes
Fixes
M0 work
```

## 6. One PR = one logical change

A PR must have a single reviewable purpose.

A PR should not simultaneously contain:

- unrelated cleanup;
- dependency upgrades unrelated to the change;
- architecture changes not described in the PR;
- opportunistic UI redesigns;
- unrelated benchmark tuning.

There is no hard line-count gate because generated code, fixtures and tests distort that metric. As a review heuristic, if a human-authored diff becomes difficult to review in one focused session, split it or explain why splitting would increase risk.

## 7. Draft PR policy

Use a draft PR early when:

- the change is architecture-significant;
- a transport/provider spike needs visibility;
- a benchmark direction is uncertain;
- CI design is being validated;
- the work spans multiple commits and early feedback reduces rework.

A draft PR is not mergeable and does not need to satisfy the final review checklist yet.

Before marking Ready for Review:

- remove obsolete experiments;
- update the PR description;
- run the required local verification;
- attach evidence required by the change class;
- ensure no known blocking TODO is hidden in the diff.

## 8. Pull request body

Every non-trivial PR must answer:

1. **Why** — what problem or hypothesis is addressed?
2. **What changed** — concise scope.
3. **What did not change** — important non-goals/boundaries.
4. **Verification** — commands/tests/scenarios run.
5. **Evidence** — benchmarks or screenshots when required.
6. **Risk** — plausible failure modes introduced.
7. **Recovery** — revert/disable/fallback path.
8. **.work impact** — whether PRODUCT/ARCHITECTURE/VERIFICATION/ROADMAP/ADR changed.
9. **Issue** — linked issue when applicable.

The repository PR template is the canonical checklist.

## 9. Required evidence by change class

### Correctness or engine changes

Require:

- automated tests covering the changed invariant;
- failure-path test when the change affects recovery/concurrency/storage;
- no known regression in deterministic benchmark fixtures relevant to the change.

### Performance/resource claims

Any claim such as:

- faster;
- lower memory;
- lower battery;
- fewer requests;
- better cache efficiency;
- better transport;
- better storage backend;

requires reproducible before/after evidence.

Commit a concise result under:

```text
.work/evidence/YYYY-MM-DD-<topic>.md
```

Raw large artifacts stay in CI.

A `perf:` PR without measured evidence is incomplete.

### Network/transport/provider changes

Require relevant deterministic failure scenarios plus a bounded real-provider compatibility smoke when appropriate.

Do not use live YouTube as the only performance benchmark.

### UI/UX changes

Require as applicable:

- screenshots for meaningful visual changes;
- screen recording for interaction/motion changes;
- accessibility consideration;
- phone-size/layout coverage;
- behavior under loading/error/offline states;
- confirmation that diagnostics complexity is not exposed in normal UX.

### Dependency changes

Require:

- reason for introducing/upgrading the dependency;
- license compatibility check for new direct dependencies;
- official release/changelog review for material upgrades;
- no unnecessary duplicate library providing functionality already available in the stack.

## 10. Architecture and .work changes

The `.work` hierarchy is normative.

Architecture-significant changes require either:

- an ADR plus the canonical document update in the same PR; or
- a direct canonical document update when the decision is small and self-contained.

Use an ADR for decisions that:

- have meaningful alternatives;
- are expensive to reverse;
- cross subsystem boundaries;
- define a new persistent format/protocol;
- introduce a major dependency;
- alter a non-negotiable invariant;
- intentionally reject a measured alternative.

An ADR is not allowed to silently contradict `.work/PRODUCT.md` or `.work/ARCHITECTURE.md`. Accepted decisions must be consolidated into canonical docs when they alter them.

No implementation PR may knowingly contradict `.work` and leave the contradiction undocumented.

## 11. Review policy

Before M0 CI exists, PR #1 is documentation-only and is reviewed manually.

After M0:

- required checks must pass;
- all review conversations must be resolved;
- branch must be mergeable against current `main`;
- requested changes are blockers until resolved/dismissed with rationale.

### Current solo-maintainer mode

Do **not** require one approving review in the `main` ruleset while the repository has only one active maintainer. GitHub does not allow a PR author to approve their own PR, so such a rule would deadlock maintainer-authored changes.

Instead require:

- PR before merge;
- checks;
- resolved conversations;
- self-review using the PR checklist.

External contributor PRs require explicit maintainer review by policy.

### Multi-maintainer mode

When a second active maintainer exists, update the ruleset to require:

- at least 1 approving review;
- dismiss stale approvals when code changes after approval;
- code-owner review for genuinely critical paths if CODEOWNERS is introduced.

Do not create CODEOWNERS merely for appearance; it becomes useful only when ownership can be assigned to more than one qualified reviewer.

## 12. Merge policy

Only **Squash and merge** is allowed for normal PRs.

Disable:

- merge commits;
- rebase-and-merge.

Reasons:

- one PR represents one logical change;
- `main` remains linear and readable;
- draft/fixup branch commits do not pollute release history;
- PR title provides a Conventional Commit-compatible canonical change record.

Before merge:

- final PR title must be correct;
- final PR body must reflect the actual diff;
- required checks must be green;
- unresolved comments must be zero;
- temporary debug code/artifacts must be removed.

After merge, delete the head branch automatically.

## 13. Reverts and regressions

A broken `main` is repaired by the safest small change.

Preferred order:

1. revert the offending PR when cause/fix is uncertain;
2. use a narrowly scoped fix PR when the fix is obvious and lower-risk than revert;
3. investigate the root cause in a follow-up issue.

Revert PRs use:

```text
revert(scope): revert <change>
```

Do not stack speculative fixes onto a broken `main`.

Performance regressions that cross an established gate are treated like correctness regressions.

## 14. GitHub main ruleset

Target: `main`.

Enable now or as soon as the repository setting is available:

- require a pull request before merging;
- require linear history;
- require conversation resolution;
- block force pushes;
- block branch deletion;
- require signed commits on `main` if the chosen squash flow remains verified in practice.

Enable once M0 creates stable check names:

- require status checks;
- require branch to be up to date before merging while project concurrency is low.

Approval count:

- 0 while there is one active maintainer;
- 1 when there are at least two active maintainers.

Do not enable merge queue yet. Enable it when concurrent PR traffic/merge races make repeated "update branch + rerun CI" materially costly.

Avoid routine bypass actors. If an emergency requires temporarily changing repository protection, document the incident and restore the rules immediately.

## 15. Repository pull-request settings

Recommended settings:

- allow **Squash merging** only;
- default squash message based on PR title;
- automatically delete head branches after merge;
- disable merge commits;
- disable rebase merging.

## 16. GitHub Actions security policy

When CI is added in M0:

- default `GITHUB_TOKEN` permission should be read-only;
- workflow/job permissions are elevated only where explicitly required;
- third-party actions are pinned to a full-length commit SHA;
- avoid `pull_request_target` for workflows that execute untrusted PR code;
- secrets are never exposed to untrusted fork code;
- prefer official or organization-controlled actions where practical;
- dependency and action updates are handled through reviewed PRs.

Dependabot configuration is added with the build in M0, not before a package ecosystem exists. Group routine version updates to reduce PR noise; security updates remain high-priority.

## 17. Release/versioning policy

No fake semantic versions are created before a distributable product exists.

When releases begin:

- use SemVer for public application/release contracts;
- release tags are immutable;
- release notes are generated from merged PRs/Conventional Commit history and curated for users;
- benchmark evidence required by release gates is linked from the release evidence document.

Do not equate every internal module refactor with a public breaking change.

## 18. Agent and automation policy

Coding agents must follow the same repository rules as humans.

Before changing code, agents must read the relevant `.work` documents.

Agents must not:

- push directly to `main`;
- broaden scope without recording the new work;
- weaken tests to make CI green;
- change architecture silently;
- assert a performance improvement without evidence;
- fabricate benchmark results;
- introduce credentials/secrets;
- modify unrelated areas "while here".

If verification cannot be performed, the PR must state exactly what remains unverified.

## 19. Source-of-truth settings checklist

These settings cannot be enforced by Markdown alone and must be configured in GitHub:

- [ ] `main` branch ruleset enabled.
- [ ] Pull request required.
- [ ] Force pushes blocked.
- [ ] Branch deletion blocked.
- [ ] Linear history required.
- [ ] Conversation resolution required.
- [ ] Squash merge enabled; merge/rebase methods disabled.
- [ ] Auto-delete merged branches enabled.
- [ ] Default Actions token read-only.
- [ ] Full-SHA action pinning policy enabled if available for the repository/org.
- [ ] Stable CI checks added to the ruleset after M0 creates them.
- [ ] Dependabot alerts/security updates enabled when dependencies exist.

Any deliberate deviation must be recorded in this file or an ADR.

## References

- GitHub rulesets: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets
- GitHub available rules: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets
- GitHub merge methods: https://docs.github.com/en/pull-requests/reference/pull-request-merges
- GitHub Actions secure use: https://docs.github.com/en/actions/reference/security/secure-use
- Conventional Commits 1.0.0: https://www.conventionalcommits.org/en/v1.0.0/
