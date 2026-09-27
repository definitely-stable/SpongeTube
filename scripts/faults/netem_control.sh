#!/usr/bin/env bash
set -euo pipefail

COMMAND="${1:?command required}"
shift

NS="spt-m2e0"
LAB_IF="sptm2e0l"
MEDIA_PORT="18081"
TC_BIN="${M2_TC_BIN:-tc}"

require_ready() {
  command -v sudo >/dev/null
  command -v ip >/dev/null
  if [[ "$TC_BIN" == */* ]]; then
    test -x "$TC_BIN"
  else
    command -v "$TC_BIN" >/dev/null
  fi
  sudo -n true
  ip netns list | awk '{print $1}' | grep -Fxq "$NS"
}

require_uint() {
  [[ "${1:-}" =~ ^[0-9]+$ ]] || {
    echo "expected non-negative integer, got: ${1:-<missing>}" >&2
    exit 2
  }
}

require_ppm() {
  require_uint "$1"
  (( $1 <= 1000000 )) || {
    echo "ppm must be <= 1000000, got: $1" >&2
    exit 2
  }
}

ppm_to_percent() {
  local ppm="$1"
  require_ppm "$ppm"
  local whole=$((ppm / 10000))
  local remainder=$((ppm % 10000))
  if (( remainder == 0 )); then
    printf '%d%%' "$whole"
    return
  fi
  local fraction
  printf -v fraction '%04d' "$remainder"
  while [[ "$fraction" == *0 ]]; do
    fraction="${fraction%0}"
  done
  printf '%d.%s%%' "$whole" "$fraction"
}

tc_ns() {
  sudo -n ip netns exec "$NS" "$TC_BIN" "$@"
}

require_ethtool() {
  command -v ethtool >/dev/null || {
    echo "ethtool is required for canonical NETWORK packetization control" >&2
    exit 2
  }
}

ethtool_ns() {
  sudo -n ip netns exec "$NS" ethtool "$@"
}

inspect_offloads() {
  require_ethtool
  local raw
  raw="$(ethtool_ns -k "$LAB_IF")"
  python3 - "$raw" <<'PY'
import json, sys
wanted = {
    "generic-receive-offload": "gro",
    "generic-segmentation-offload": "gso",
    "tcp-segmentation-offload": "tso",
}
values = {}
for line in sys.argv[1].splitlines():
    if ":" not in line:
        continue
    key, rest = line.strip().split(":", 1)
    if key not in wanted:
        continue
    token = rest.strip().split()[0]
    if token not in {"on", "off"}:
        raise SystemExit(f"unexpected ethtool state for {key}: {rest!r}")
    values[wanted[key]] = token == "on"
missing = sorted(set(wanted.values()) - set(values))
if missing:
    raise SystemExit(f"missing ethtool offload state: {missing}")
print(json.dumps(values, sort_keys=True))
PY
}

prepare_fidelity() {
  require_ethtool
  ethtool_ns -K "$LAB_IF" gro off gso off tso off
  local state
  state="$(inspect_offloads)"
  python3 - "$state" <<'PY'
import json, sys
state = json.loads(sys.argv[1])
enabled = sorted(key for key, value in state.items() if value)
if enabled:
    raise SystemExit(f"packetization offloads still enabled: {enabled}")
PY
}

clear_root() {
  tc_ns qdisc del dev "$LAB_IF" root 2>/dev/null || true
}

install_scoped_netem() {
  clear_root
  # All unmatched skb priorities bypass band 1:1. Only the flower rule below
  # may steer media responses into the impaired band.
  tc_ns qdisc add dev "$LAB_IF" root handle 1: prio bands 3 \
    priomap 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2
  tc_ns qdisc add dev "$LAB_IF" parent 1:1 handle 10: netem "$@"
  tc_ns filter add dev "$LAB_IF" parent 1: protocol ip pref 10 flower     ip_proto tcp src_port "$MEDIA_PORT" flowid 1:1
}

apply_delay_jitter() {
  local delay_us="${1:?delayUs required}"
  local jitter_us="${2:?jitterUs required}"
  local correlation_ppm="${3:?correlation ppm required}"
  local seed="${4:?seed required}"
  require_uint "$delay_us"
  require_uint "$jitter_us"
  require_ppm "$correlation_ppm"
  require_uint "$seed"
  (( delay_us > 0 && jitter_us > 0 )) || {
    echo "delay/jitter must be positive" >&2
    exit 2
  }
  install_scoped_netem     delay "${delay_us}us" "${jitter_us}us" "$(ppm_to_percent "$correlation_ppm")"     seed "$seed"
}

apply_blackout() {
  local loss_ppm="${1:?lossPpm required}"
  require_ppm "$loss_ppm"
  (( loss_ppm == 1000000 )) || {
    echo "canonical blackout requires lossPpm=1000000" >&2
    exit 2
  }
  install_scoped_netem loss random 100%
}

apply_burst_loss() {
  local loss_ppm="${1:?lossPpm required}"
  local correlation_ppm="${2:?correlation ppm required}"
  local seed="${3:?seed required}"
  require_ppm "$loss_ppm"
  require_ppm "$correlation_ppm"
  require_uint "$seed"
  (( loss_ppm > 0 && loss_ppm < 1000000 )) || {
    echo "seeded burst loss must be between 0 and 1000000 ppm" >&2
    exit 2
  }
  install_scoped_netem     loss random "$(ppm_to_percent "$loss_ppm")" "$(ppm_to_percent "$correlation_ppm")"     seed "$seed"
}

inspect_qdisc() {
  tc_ns -s -j qdisc show dev "$LAB_IF"
}

inspect_filter() {
  tc_ns -j filter show dev "$LAB_IF" parent 1:
}

assert_clean() {
  local qdisc filter
  qdisc="$(inspect_qdisc)"
  filter="$(inspect_filter 2>/dev/null || printf '[]')"
  python3 - "$qdisc" "$filter" <<'PY'
import json, sys
qdiscs = json.loads(sys.argv[1])
filters = json.loads(sys.argv[2])
if any(isinstance(x, dict) and x.get("kind") in {"netem", "prio"} for x in qdiscs):
    raise SystemExit("NETWORK qdisc leaked")
if filters:
    raise SystemExit("NETWORK filter leaked")
PY
}

require_ready
case "$COMMAND" in
  apply-delay-jitter)
    apply_delay_jitter "$@"
    ;;
  apply-blackout)
    apply_blackout "$@"
    ;;
  apply-burst-loss)
    apply_burst_loss "$@"
    ;;
  inspect-qdisc)
    inspect_qdisc
    ;;
  inspect-filter)
    inspect_filter
    ;;
  prepare-fidelity)
    prepare_fidelity
    ;;
  inspect-offloads)
    inspect_offloads
    ;;
  remove)
    clear_root
    ;;
  assert-clean)
    assert_clean
    ;;
  *)
    echo "unsupported command: $COMMAND" >&2
    exit 2
    ;;
esac
