# M3-A2-C2 — archived-evidence stop gate (2026-11-01)

Status: **C2 diagnostic loop STOP in the identical frozen environment only**.
Owner: [#158](https://github.com/definitely-stable/SpongeTube/issues/158).
Parent: #144 / #50. Independent production-policy gate: #52.

## Decision reached and what was *not* decided

D0–D7 investigated repeated missing media metadata in a pinned anonymous
yt-dlp reference with JS/EJS disabled, no PO-token provider and no account.
D5/D6/D7 independently retained canonical original GitHub Actions artifact
ZIP checksums, typed measurements and a shared fixed four-cell control set.
The current **offline** gate executes a consistency check over the archived
D5/D6/D7 machine-readable ledgers, then returns exactly:

- C2: **STOP_C2_SAME_ENVIRONMENT_ONLY** — further *serial source-shape* probes
  with the same four V0/V2 × WEB_EMBEDDED/TV controls, pinned reference and
  declared runner mode are not justified by an independent falsifiable question.
- Overall M3-A2: **NOT_MADE_M3_A2** — the original 18-cell matrix and V1
  split A/V capability have not been completed or positively verified.
- M3-A3 and M3-B: **BLOCKED** — zero observed media and no supported
  production adapter selection. Provider-wide NO-GO is **not** established.
- Policy #52: **OPEN** — licence and distribution issues remain independent
  of the negative measurements.

It does **not** state that all YouTube video formats are absent, infer the
absence of streaming data on the network wire, prove JS or PO tokens necessary,
or authorize challenge/anti-abuse bypass.

## Proof boundary and independent checks

Run offline:

```text
python3 scripts/measurement/m3_c2_stop_gate.py verify
python3 scripts/measurement/m3_c2_stop_gate.py render
python3 -m unittest discover -s scripts/measurement/tests -p 'test_m3_c2_stop_gate.py' -v
```

Inputs are **unchanged** already-archived D5/D6/D7 digest ledgers and frozen
`.work/m3-a2/c2-stop-gate-v1.json`. The verifier asserts exact GitHub run,
artifact and source SHAs, expected ZIP/manifest SHA-256 strings, 4 ordered
cases, matching oracle and exact frozen runner image, 16 metadata calls/0 media per slice,
closed D5 terminal, D6 input-shape, D7 pre-strip embedded/API + HLS/DASH
manifest observations, and no hidden extra fields. Synthetic falsification
rejects fabricated media, token, manifest, source-presence, provenance and
production decisions, wrong controls and weakened budgets.

**Important distinction:** this offline ledger verifier does **not**
redownload original ZIPs or cryptographically validate ZIP member bytes.
The original independent original-ZIP audits live in the canonical D5/D6/D7
reports and GitHub Actions artifact service; the stored ledger hashes here
are consistency anchors, not a replacement for those audits. The gate is
deterministic and completely free of live provider I/O.

## Reopen only with new independent information

Reopening research requires a *new, predeclared, individually reviewed*
hypothesis, a material independent input (for example a legitimately
accessible, separately pinned and policy-reviewed reference/environment),
one controlled changed variable, retained exact provenance, bounded
requests, and clear pass/fail interpretations. Compare against the existing
D5–D7 evidence without silently changing the old case registry or records.
No provider-traffic retry loops, hidden VPN/egress bypass, account credentials,
runtime/token generator adoption, or production dependency without separately
approved policy and technical gates.

**Next independently productive workstream:** #52 licensing/distribution
inventory and product shipping constraints can be investigated entirely
offline and does not require more YouTube probes. Owner decisions on project
licence and intended release channels still need explicit approval.

This closes **C2 diagnostic escalation**, not #158/#144/#50 and not M3-A2.
