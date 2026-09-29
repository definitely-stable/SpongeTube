# ADR-0006: Evaluate exact-route transport backends before production selection

Status: Accepted
Date: 2026-09-29
Owners: M2-G / #109
Supersedes: none

## Context

M2-F now proves that a permitted Android default route must be carried into the
physical media request. M2-E provides scoped TRANSPORT and NETWORK fault
harnesses. The remaining M2 transport question is therefore not "which client
is fastest in a synthetic download", but whether a candidate can preserve the
same immutable work, route privacy, bounded RecoveryChain semantics and
publication path while improving or preserving playback-oriented behavior.

Android's current Media3 guidance recommends platform HttpEngine on supported
devices. HttpEngine is available from API 34 (and S extensions 7) and can bind
an engine or individual request to an Android Network. Cronet exposes equivalent
request/network binding from Marshmallow. These capabilities make exact-route
evaluation feasible without process-wide network binding.

The project minimum API remains 23, so no API-34-only result can silently erase
the compatibility path.

## Decision

### Evaluate before adopting

M2-G does not replace the production transport in its contract slice. It first
builds paired evidence under the frozen experiment rule:

```text
same fixture + scenario + device state + playback mode + cache state
+ recovery policy + route policy + connection state + ordering protocol
only backend differs
```

Every candidate must use the exact Android Network approved by M2-F. Process-wide
`bindProcessToNetwork` is forbidden.

### Candidate order

The required first pair is:

1. `HTTP_URL_CONNECTION_ROUTE_BOUND` — current control.
2. `PLATFORM_HTTP_ENGINE` — first modern candidate on eligible devices.

`OKHTTP_5` and direct `CRONET` are conditional candidates. They are adopted
into the experiment only after a measured compatibility/resilience question
justifies the extra runtime dependency or implementation complexity. G0 adds no
third-party networking dependency.

This follows #23: platform HttpEngine first, then third-party alternatives only
when evidence identifies a concrete reason.

### Correctness before performance

A trial that violates range correctness, publication correctness, exact-route
binding or RecoveryChain ownership is not a performance sample.

Backend availability is eligibility evidence, not latency. An unavailable
backend records `UNAVAILABLE_ON_DEVICE` and contributes no timing sample.

Negotiated HTTP protocol is descriptive evidence. HTTP/2 or HTTP/3 is never a
score by itself.

### Ordering and warm state

Every comparison declares connection state (`COLD` or `WARM`) and uses a
counter-balanced or persisted-seed balanced ordering protocol. A run may not
compare a cold control against a warm candidate.

### Internal recovery visibility

Transport-internal connection behavior is observed where the platform exposes
it and correlated with origin request counts. Opaque internal recovery is a
limitation that can block a resilience-equivalence claim. It never grants a new
Sponge RecoveryChain budget and never authorizes the client stack to become a
second application-level retry owner.

### Claim boundary

Emulator runs may prove correctness, route binding, recovery equivalence and
directional emulator measurements. They are not representative battery,
thermal or physical-device performance evidence.

A performance-based `SELECTED` decision requires physical-device evidence.
A correctness/resilience selection may be made without a performance claim only
when the decision record says so explicitly and dependency/distribution policy
is clear.

## Rejected alternatives

- Select a winner from synthetic throughput: does not represent SpongeTube's
  playback/recovery objective.
- Add OkHttp, Cronet Embedded and HttpEngine simultaneously: increases the
  dependency/build surface before a measured question exists.
- Use Media3 DefaultHttpDataSource as an ambient-route fallback: violates the
  exact-route invariant unless an owning implementation proves equivalent
  per-request binding.
- Bind the whole process to a Network: breaks M2-F isolation and can affect
  control/provider traffic.
- Treat QUIC/HTTP3 negotiation as proof of superiority: protocol identity alone
  does not prove playback resilience.
- Compare absolute host and Android timestamps: violates the M2 clock-domain
  contract.

## Consequences

M2-G starts with a dependency-free contract and an API36 platform comparison.
The evidence format can later admit OkHttp/Cronet without changing comparison
semantics. A candidate that cannot prove exact-route/recovery equivalence may be
reported as technically ineligible rather than being forced into a ranking.
