# Evidence: M2-E scoped transport / packet fault harness

Date: **2026-09-28**

Status: **PASS — M2-E acceptance evidence closure**

Parent: #85  
Closure slice: #91

## Question

Does SpongeTube have a scoped, attributable and independently verified M2-E
laboratory for TRANSPORT and NETWORK faults that preserves the existing
RecoveryChain / REMOTE_ATTEMPT ownership, never publishes incomplete media,
keeps ADB/control outside the impaired media path and cleans the laboratory
state after every run?

## Hypothesis

For each canonical M2-E scenario:

1. exactly one external harness owns the injected fault plane;
2. the applied tool state is independently read back and matches the resolved
   scenario;
3. Android sees ordinary runtime transport observations rather than a synthetic
   packet-loss/provider classification;
4. recovery stays on the existing M2-C chain/budget;
5. incomplete/corrupt media is never published;
6. ADB/control/artifact paths remain healthy;
7. cleanup removes the injected state;
8. NETWORK seed evidence proves seed binding only; correlated netem profiles do
   not claim identical per-packet replay unless separately demonstrated.

## Implementation chain

| Slice | PR | Exact implementation head | Squash merge |
| --- | ---: | --- | --- |
| E0 topology feasibility | #86 | `1a7c1b3b651b75cde36d757bd2229dfc0a958e8d` | `b9b7c4e46af4197c8f2e9fcd21ca0ed2921ed923` |
| E1 contract freeze | #87 | `8f1eb963785f5cd9e4c394d025451e4480e5d69c` | `b76651b6160aa6a7db9367017e766acc14723f10` |
| E2 TRANSPORT harness | #88 | `483c2dc03552812bb02e05deb9d8944b3d93a209` | `23d7c474dccead0b5c10bbb60f1ebf1b3e20d00e` |
| E3 NETWORK harness | #90 | `d6d62f3534895837c82ccb6a4c09e42a056055a8` | `12abdb89e14d48fd66bcb2ac791e18bca89f4710` |

The E2/E3 run manifests intentionally bind the exact tested PR head. The table
above records the squash merge that carried that tested implementation into
`main`.

## Environment and toolchain

### E0

E0 retained feasibility evidence is
`.work/evidence/2026-09-27-m2-e0-topology-feasibility.md`.
It proved the direct API 36 emulator -> isolated namespace/veth media path,
control-only `adb reverse`, media qdisc scoping and teardown.

### TRANSPORT

- harness: `sponge-transport-harness` v1;
- tool: Toxiproxy **2.12.0**;
- Toxiproxy remains a laboratory-only stream/connection fault owner;
- packet-loss/latency toxics are not canonical NETWORK evidence.

### NETWORK

The retained E3 artifacts report one common fault-engine fingerprint for
N2/N3/N5:

- runner image: `ubuntu24` / `20260920.314.1`;
- Ubuntu: 24.04;
- kernel: `6.17.0-1022-azure`, x86_64;
- canonical tc: `tc utility, iproute2-6.6.0`;
- pinned iproute2 source SHA-256:
  `8738c804afd09f0bf756937f0c3de23117832a98d8cbbf50386cf5005cd613ce`;
- ethtool: `ethtool version 6.7`;
- isolated lab-veth offloads: `GRO=false`, `GSO=false`, `TSO=false`;
- fingerprint document SHA-256:
  `d1ae069acfadc3e5cd3b79c8d20d207ece65e175642bb91b9202c0cb92c5197d`.

Each NETWORK `m2-run-manifest-v1` contains the same
`runtime.faultEngineFingerprintSha256`, independently recomputed from the
retained fingerprint document.

## Canonical TRANSPORT results

Workflow: **M2-E Transport Faults**, run **36329768806**  
Tested head: `483c2dc03552812bb02e05deb9d8944b3d93a209`

| Scenario | Scenario SHA-256 | Toxiproxy state | Physical attempts | Terminal | Published | Fault oracle |
| --- | --- | --- | ---: | --- | --- | --- |
| N6 `TRANSPORT_READ_TIMEOUT` | `82ed5d21f83d776c1c7e2746f53dbb777163f1e89895115d2848df4d3ae74b84` | downstream `timeout`, timeout=0 | 4 | `BUDGET_EXHAUSTED` | false | PASS |
| N6 `TRANSPORT_RESET` | `12d6a0aebb9c021f89170dd429356bf29c8822f482f687cc00992cf26d316d45` | downstream `reset_peer`, timeout=0 | 4 | `BUDGET_EXHAUSTED` | false | PASS |
| N6 `TRUNCATED_STREAM` | `bec1b9e6e7f55d21815de4af59cf7007d187a37eaac1b2fcc74c867ea64fe505` | downstream `limit_data`, bytes=16384 | 4 | `BUDGET_EXHAUSTED` | false | PASS |
| N6 `SLOW_CLOSE` | `da0159eaa964add50477be7423f3ee882d70ae38c6c37929352c9706f8f95b1b` | downstream `slow_close`, delay=1000 | 1 | `SUCCESS` | true | PASS |

All four retained `fault-verification-summary-v1` documents report:

- `status=PASS`;
- `primaryPlane=TRANSPORT`;
- all verification checks true;
- `M2-ACC-01=true`;
- `M2-ACC-02=true`;
- `M2-ACC-09=true`.

The timeout, reset and truncation cases also retain independent
`recovery-verification-summary` evidence for M2-ACC-05/06. Each used one
RecoveryChain, four charges == four physical attempts, a monotonic ledger and no
budget reset between owner lifetimes. Truncation never published the incomplete
extent. Slow close is intentionally a lifecycle proof: a complete fixed-length
body may already be valid before the delayed close.

## Canonical NETWORK results

Workflow: **M2-E Network Fault Harness**, run **36341023295**  
Tested head: `d6d62f3534895837c82ccb6a4c09e42a056055a8`

Every canonical NETWORK scenario uses:

- `direction=DOWNSTREAM`;
- `ipFamily=IPV4`;
- `l4Protocol=TCP`;
- `scope=MEDIA_DATA_ONLY`;
- one flower classifier for source port 18081;
- classifier target `1:1`, the only impaired prio band;
- unmatched traffic mapped to the bypass band;
- no media `adb reverse`;
- canonical GRO/GSO/TSO-off packetization state.

| Scenario | Scenario SHA-256 | Observed qdisc effect | Attempts / failures | Terminal | Published | Fault oracle |
| --- | --- | --- | --- | --- | --- | --- |
| N2 `HIGH_RTT_JITTER` | `0a4b3ec961d3925bbe46d58697c9b0031d8f970ec45845ffe2e3c96a1e83b8ac` | delay=100000 us, jitter=30000 us, correlation=250000 ppm, seed=424242; packets=64 | 1 / 0 | `SUCCESS` | true | PASS |
| N3 `BURST_PACKET_LOSS` | `c8a2ec3ab7f337b287b20f0ed19de6e1e7f1cfb4c7dae295602a5d4f9137c990` | loss=1000000 ppm; drops=4, transmitted packets=0 | 4 / 3 | `SUCCESS` | true | PASS |
| N5 `BURST_LOSS` | `aed0b51ca0f0f5b0409918e1b621ef67d4c7655983bf839f58699c171977d670` | loss=20000 ppm, correlation=250000 ppm, seed=424242; packets=64 | 1 / 0 | `SUCCESS` | true | PASS |

All three retained `fault-verification-summary-v1` documents report
`status=PASS`, all checks true and M2-ACC-01/02/09 true.

### N3 traffic-rendezvous proof

The N3 harness does not start its 1500 ms canonical interval at process launch.
It first installs/read-backs the scoped 100% loss qdisc and emits
`FAULT_ARMED`; Android then starts the media request. The first observed
matching qdisc drop transitions the lifecycle to `FAULT_APPLIED`.

Retained lifecycle timestamps:

- `FAULT_ARMED`: 288724202268 ns;
- `FAULT_APPLIED`: 302852939238 ns;
- `FAULT_REMOVED`: 304555257406 ns.

Measured applied interval:

`FAULT_REMOVED - FAULT_APPLIED = 1,702,318,168 ns`

which exceeds the required 1,500,000,000 ns. The final qdisc evidence reports
`drops=4` and `packets=0`. This is accepted intentionally: a 100% loss
netem may drop matching skb objects before they contribute to transmitted packet
statistics. The independent oracle therefore verifies the actual loss effect
rather than incorrectly requiring `packets > 0`.

Android recorded:

- physical attempts: 4;
- failures: 3;
- timeout failures: 3;
- terminal: `SUCCESS`;
- published: true.

Thus N3 proves a real NETWORK-caused transport failure/recovery path and not
merely successful netem configuration.

### N2/N5 seed semantics

N2 and N5 prove that:

- the canonical resolved scenario persists seed 424242;
- the harness receives that seed;
- tc readback reports that seed;
- the independent oracle binds the observed state to the same scenario hash.

They do **not** claim that a correlated netem profile reproduces an identical
per-packet sequence from that seed alone. Correlated netem state may include
kernel entropy. Exact effect-sequence reproducibility requires a separately
proven profile/model and is deferred rather than overstated here.

N5 produced zero drops in this short end-to-end fixture run. That is not treated
as falsification: E3 uses this case to prove scoped stochastic configuration,
seed binding, path traversal and correctness under the configured profile. A
long packet-train / effect-sequence calibration is outside E4 and belongs to
future network-performance/fidelity research rather than changing this
acceptance claim retroactively.

## Scope and isolation proof

NETWORK calibration reports all of the following for N2/N3/N5:

- media namespace counters advanced;
- Media Lab control-loopback counters advanced independently;
- ADB healthy before/during/after;
- qdisc removed;
- flower filters removed;
- namespace removed;
- no Toxiproxy process/toxic owned the NETWORK run.

The E3 workflow additionally falsifies classifier leakage: non-media traffic
must remain in the bypass band and must not start the N3 blackout epoch.

## Artifact retention and digest verification

The following GitHub Actions artifacts were downloaded during E4 closure and
their ZIP SHA-256 values were recomputed locally. Every recomputed value matched
the digest reported by GitHub.

| Run | Artifact | Artifact ID | GitHub / recomputed SHA-256 |
| --- | --- | ---: | --- |
| 36329768806 | `m2-e-transport-api36` | 10935631425 | `da999d1a39a7dba045195b6c472d5aff17bafc7402a6f8133b69c466ca02fd1e` |
| 36329768806 | `m2-e-transport-host` | 10934508750 | `87966d1ae6becc11c84a69580d95c37dad4f12304b89e9327aee6b731634af00` |
| 36341023295 | `m2-e-network-api36` | 10938908007 | `1490957d16d5691dd692221c98c452a22de8e92db0a141ff438d6fe44970ef14` |
| 36341023295 | `m2-e-network-host` | 10938688792 | `c6140ab7061be141217222c6708a09b819902992eccc307b87d3c9f326fb45d7` |

These workflow artifacts have finite CI retention. This committed evidence
summary preserves the run/artifact identities, hashes and normalized acceptance
facts; large raw logs/traces remain CI artifacts by repository policy.

## Acceptance

| Gate | Result | Evidence |
| --- | --- | --- |
| M2-ACC-01 Scenario Identity | **PASS** | scenario hash bound in run manifest + harness + verifier; stochastic seeds persisted/applied |
| M2-ACC-02 Fault Attribution | **PASS** | one primary plane and owning harness; NETWORK-induced runtime failures remain TRANSPORT observations |
| M2-ACC-05 Failure Separation | **PASS / retained regression** | independent M2-C recovery verifier remains green |
| M2-ACC-06 Bounded Recovery Lineage | **PASS / retained regression** | one chain, monotonic REMOTE_ATTEMPT ledger, max 4 physical attempts |
| M2-ACC-07/08 | **PASS / retained regression** | M2-D provider regression workflow remained green during E3 |
| M2-ACC-09 Scoped Fault Harness Fidelity | **PASS** | tool readback, scope, packetization state, traffic traversal, control isolation and cleanup independently verified |

## Decision

**M2-E is accepted as complete.**

Accepted architecture:

- DELIVERY/PROVIDER faults remain Media Lab / provider-simulator owned;
- TRANSPORT faults are owned by pinned Toxiproxy 2.12.0;
- NETWORK faults are owned by scoped Linux tc/netem on the isolated media path;
- RecoveryCoordinator remains the sole logical retry owner;
- no new production networking dependency or retry policy was introduced.

The next milestone slice is M2-F Android Route/VPN Recovery Integration. M2-G
retains responsibility for transport comparison and representative performance
evidence.

## Limitations

This evidence establishes correctness, attribution, bounded recovery and
laboratory fidelity for the tested API 36 emulator/runner environment. It does
not establish:

- representative Wi-Fi/LTE/5G performance;
- physical-device battery or thermal behavior;
- a winning production transport;
- live YouTube/provider semantics;
- Android VPN/default-route recovery;
- exact per-packet replay of correlated netem effects;
- long-run stochastic loss-distribution calibration.

Those claims remain outside M2-E.
