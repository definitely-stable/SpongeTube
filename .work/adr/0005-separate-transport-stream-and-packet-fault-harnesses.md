# ADR-0005: Separate transport-stream and packet/network fault harnesses

Status: Accepted
Date: 2026-09-27
Owners: M2-E / #85
Supersedes: none

## Context

M2 must exercise transport failures and packet/network impairments without
collapsing their causal ownership into one generic "bad network" layer.
M2-A froze one primary owning plane per injected fault. M2-C already consumes
typed runtime observations and owns bounded recovery; M2-E therefore needs an
external laboratory that changes causes, not recovery policy.

The E0 feasibility gate (#86, merge `b9b7c4e4`) proved on GitHub-hosted
`ubuntu-24.04` plus the API 36 emulator that media traffic can reach an
isolated Linux network namespace over a dedicated veth without media
`adb reverse`, while ADB and Media Lab control remain outside the impaired
path. E0 also proved a pinned Toxiproxy 2.12.0 binary, scoped `tc/netem`,
machine-readable qdisc/filter readback, explicit netem seed support and clean
teardown.

## Decision

Use two external harnesses with non-overlapping ownership.

### TRANSPORT

Use pinned Toxiproxy 2.12.0 only for TCP/stream-semantic faults used by M2-E:
connection timeout, reset, downstream data truncation and slow close.

Toxiproxy is a laboratory tool only. It is not an Android/runtime dependency
and never decides retries. Toxiproxy packet-loss/latency/bandwidth style toxics
are not accepted as canonical NETWORK evidence.

### NETWORK

Use Linux `tc/netem` on an isolated namespace/veth media path. Network faults
include delay/jitter, packet loss, corruption, duplication, reordering and
rate. Stochastic configurations use the persisted M2 scenario seed where the
kernel primitive supports one. Direction, IP family and L4 protocol are part of
the resolved NETWORK scenario identity rather than implicit harness defaults.
Seed read-back proves that the requested seed reached netem; correlated netem
modes are not treated as bit-for-bit effect replay unless that stronger property
is separately demonstrated.

Never attach the M2 qdisc globally to runner loopback or the runner's primary
network interface. ADB, Media Lab control, fault-control traffic and artifact
collection remain outside the impaired media path.

### Media Lab

Media Lab remains the DELIVERY/PROVIDER owner and deterministic HTTP origin. It
does not inject packet loss, TCP reset or other M2-E transport/network faults.
A minimal bind-address extension is permitted only when needed to place the
origin in the proven namespace topology.

### Evidence

The resolved `m2-scenario-v1` is authoritative input. The harness emits
`fault-harness-events-v1`; NETWORK runs additionally emit
`network-calibration-v1`. An independent oracle emits
`fault-verification-summary-v1`.

All harness timestamps use `HOST_FAULT_MONOTONIC`. Android and host absolute
monotonic values are never compared arithmetically; cross-domain joins use
identities/correlation.

Tool command success is insufficient. The harness must read back normalized
tool state and prove cleanup. Portable evidence never retains raw IP addresses,
URLs, shell commands or credentials.

## Rejected alternatives

- One universal fault tool: obscures fault-plane ownership.
- Global `netem` on `lo` or the runner primary interface: can perturb ADB,
  control traffic and GitHub orchestration.
- Toxiproxy packet-loss as the canonical NETWORK oracle: operates at the stream
  proxy layer rather than the scoped packet/network layer frozen by M2.
- Android Emulator `-netdelay` / `-netspeed` as canonical M2-E evidence:
  does not provide the required scoped, independently read-back fault plane.
- `adb reverse` for media-under-test NETWORK scenarios: bypasses the proven
  direct media path and cannot establish packet-path scope.

## Consequences

M2-E gains reproducibly configured and attributable laboratory causes without
adding a new production networking dependency or a second retry owner. Emulator results
remain correctness/fault-attribution evidence only; transport selection and
representative performance conclusions remain M2-G.
