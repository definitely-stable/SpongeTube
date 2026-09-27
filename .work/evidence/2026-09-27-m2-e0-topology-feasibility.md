# Evidence: M2-E0 scoped transport / packet fault topology feasibility

Date: **2026-09-27**

Status: **Feasibility gate PASS — not M2-E acceptance**

## Question

Can the GitHub-hosted Ubuntu 24.04 + API 36 emulator lab provide a scoped,
deterministic fault plane in which media traffic reaches an isolated namespace
through a dedicated veth without media `adb reverse`, while ADB, control and
artifact collection stay outside the impairment path?

## Result

Yes for the E0 topology. The measured implementation head
`fb4fb9227c6b5123ac8bd5069a73e128ad5d23be` passed workflow run
`36312897397`.

This result proves infrastructure feasibility only. It does **not** claim
M2-ACC-09, canonical TRANSPORT/NETWORK scenarios, recovery correctness under
those scenarios, or completion of M2-E.

## Scope and invariants

- No production runtime source changed.
- Media under test used normal emulator outbound networking to the isolated
  namespace endpoint; media `adb reverse` was absent.
- `adb reverse` was used only for the control plane.
- The impairment qdisc existed only on the namespace media veth; no global
  runner `lo` / `eth0` qdisc was touched.
- TRANSPORT tooling and NETWORK tooling remain separate: Toxiproxy was only
  lifecycle/capability-probed here; Android media topology used netem only.
- Android and host fault clocks were not compared arithmetically.
- Portable evidence contains no raw IP addresses or URLs.

## Pinned toolchain measured

| Component | Measured value | Result |
| --- | --- | --- |
| Runner | Ubuntu 24.04 | PASS |
| Kernel | `6.17.0-1022-azure` | recorded |
| System `tc` | `iproute2-6.1.0, libbpf 1.3.0` | recorded |
| Lab `tc` | pinned `iproute2-6.6.0` | PASS |
| Lab iproute2 source SHA-256 | `8738c804afd09f0bf756937f0c3de23117832a98d8cbbf50386cf5005cd613ce` | PASS |
| Toxiproxy | `2.12.0`, `toxiproxy-server-linux-amd64` | PASS |
| Toxiproxy SHA-256 | `556d891134a3c582dc1e1a3f7335fd55142e5965769855a00b944e13e48302fc` | PASS |

The host probe read back a seeded netem qdisc with seed `424242`, plus a
`flower` egress classifier restricted to TCP responses from the dedicated
media port. Toxiproxy started with an empty proxy set and exited cleanly.

## API 36 topology proof

During the Android probe a deterministic, non-lossy `netem delay 1000us`
qdisc was active on the namespace media veth. The device successfully reached
the namespace media endpoint and separately reached Media Lab control through
the permitted control-only reverse path.

The namespace media-veth TX counter advanced from **13** to **21** packets
(**+8**) across the Android request. The active qdisc machine-readable readback
reported `kind=netem` and `delay=0.001` seconds.

Isolation checks all passed:

- ADB healthy before / during / after;
- Media Lab control healthy before / during / after;
- media reverse absent;
- Android additional-output collection healthy;
- namespace, veth and qdisc cleanup complete.

## Retained CI artifacts

Workflow: **M2-E0 Topology Feasibility**, run `36312897397`,
implementation head `fb4fb9227c6b5123ac8bd5069a73e128ad5d23be`.

| Artifact | Artifact ID | SHA-256 digest | Result |
| --- | ---: | --- | --- |
| `m2-e0-host-feasibility` | `10928619463` | `00eb144b97ea1bf79cc1465466eab7ec65e7d01b527338396fbacd146dd3aa1f` | PASS |
| `m2-e0-api36-topology` | `10929682361` | `e1e80f77f58ee84f6703715cd45bb84ef13505473097e5dd6f35097830a27a2a` | PASS |

The normalized host artifact reports `status=PASS`,
`globalRunnerQdiscTouched=false`, seeded-netem and classifier readback, zero
unexpected Toxiproxy proxies/toxics and complete cleanup. The normalized Android
artifact reports `status=PASS`, `DIRECT_NAMESPACE`,
`ADB_REVERSE_CONTROL_ONLY`, `adbReverseUsed=false`, scoped-qdisc traversal
and complete isolation/cleanup.

## Decision

E0 hard gate is satisfied for this runner/emulator topology. M2-E may proceed to
E1 contract/toolchain freeze, followed by the canonical TRANSPORT and NETWORK
fault harness work. The E0 topology must not be weakened to a media
`adb reverse` path in later slices.
