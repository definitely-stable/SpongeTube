#!/usr/bin/env bash
set -euo pipefail

COMMAND="${1:?command required}"

NS="spt-m2e0"
HOST_IF="sptm2e0h"
LAB_IF="sptm2e0l"
HOST_CIDR="192.0.2.1/30"
LAB_CIDR="192.0.2.2/30"
LAB_ADDR="192.0.2.2"
MEDIA_PORT="18081"
SEED="424242"

require_tools() {
  command -v ip >/dev/null
  command -v tc >/dev/null
  command -v sudo >/dev/null
  command -v python3 >/dev/null
  sudo -n true
}

namespace_exists() {
  ip netns list | awk '{print $1}' | grep -Fxq "$NS"
}

host_link_exists() {
  ip link show "$HOST_IF" >/dev/null 2>&1
}

terminate_namespace_processes() {
  if ! namespace_exists; then
    return 0
  fi
  mapfile -t pids < <(sudo -n ip netns pids "$NS" 2>/dev/null || true)
  if (("${#pids[@]}" == 0)); then
    return 0
  fi
  sudo -n kill -TERM "${pids[@]}" 2>/dev/null || true
  for _ in {1..20}; do
    mapfile -t pids < <(sudo -n ip netns pids "$NS" 2>/dev/null || true)
    (("${#pids[@]}" == 0)) && return 0
    sleep 0.1
  done
  sudo -n kill -KILL "${pids[@]}" 2>/dev/null || true
}

teardown() {
  terminate_namespace_processes
  if namespace_exists; then
    sudo -n ip netns delete "$NS"
  fi
  if host_link_exists; then
    sudo -n ip link delete "$HOST_IF"
  fi
}

prepare() {
  require_tools
  teardown

  sudo -n ip netns add "$NS"
  sudo -n ip link add "$HOST_IF" type veth peer name "$LAB_IF"
  sudo -n ip link set "$LAB_IF" netns "$NS"

  sudo -n ip addr add "$HOST_CIDR" dev "$HOST_IF"
  sudo -n ip link set "$HOST_IF" up

  sudo -n ip netns exec "$NS" ip addr add "$LAB_CIDR" dev "$LAB_IF"
  sudo -n ip netns exec "$NS" ip link set lo up
  sudo -n ip netns exec "$NS" ip link set "$LAB_IF" up

  ip route get "$LAB_ADDR" | grep -Fq "$HOST_IF"
  sudo -n ip netns exec "$NS" ip route get 192.0.2.1 | grep -Fq "$LAB_IF"
}

apply_probe() {
  namespace_exists || {
    echo "namespace not prepared" >&2
    exit 1
  }

  # Root netem proves this runner accepts an explicit random seed. It is
  # attached only to the dedicated namespace veth, never lo/eth0/ADB.
  sudo -n ip netns exec "$NS" \
    tc qdisc replace dev "$LAB_IF" root netem loss random 1% seed "$SEED"

  # clsact + flower proves machine-readable traffic classification is present.
  # The filter matches only responses from the dedicated media port.
  sudo -n ip netns exec "$NS" tc qdisc add dev "$LAB_IF" clsact
  sudo -n ip netns exec "$NS" \
    tc filter replace dev "$LAB_IF" egress protocol ip pref 10 flower \
    ip_proto tcp src_port "$MEDIA_PORT" action pass
}

remove_probe() {
  if namespace_exists; then
    sudo -n ip netns exec "$NS" tc qdisc del dev "$LAB_IF" clsact 2>/dev/null || true
    sudo -n ip netns exec "$NS" tc qdisc del dev "$LAB_IF" root 2>/dev/null || true
  fi
}

inspect_qdisc() {
  sudo -n ip netns exec "$NS" tc -s -j qdisc show dev "$LAB_IF"
}

inspect_filter() {
  sudo -n ip netns exec "$NS" tc -j filter show dev "$LAB_IF" egress
}

assert_probe_readback() {
  local qdisc_json filter_json
  qdisc_json="$(inspect_qdisc)"
  filter_json="$(inspect_filter)"
  python3 - "$qdisc_json" "$filter_json" <<'PY'
import json, sys
qdiscs = json.loads(sys.argv[1])
filters = json.loads(sys.argv[2])
kinds = {entry.get("kind") for entry in qdiscs if isinstance(entry, dict)}
if "netem" not in kinds or "clsact" not in kinds:
    raise SystemExit(f"missing qdisc readback: {sorted(k for k in kinds if k)}")
if not any(isinstance(entry, dict) and entry.get("kind") == "flower" for entry in filters):
    raise SystemExit("missing flower classifier readback")
PY
}

assert_clean() {
  if namespace_exists; then
    echo "namespace leaked: $NS" >&2
    exit 1
  fi
  if host_link_exists; then
    echo "veth leaked: $HOST_IF" >&2
    exit 1
  fi
}

case "$COMMAND" in
  probe)
    require_tools
    ;;
  prepare)
    prepare
    ;;
  apply-probe)
    apply_probe
    ;;
  remove-probe)
    remove_probe
    ;;
  inspect-qdisc)
    inspect_qdisc
    ;;
  inspect-filter)
    inspect_filter
    ;;
  assert-probe-readback)
    assert_probe_readback
    ;;
  media-address)
    printf '%s\n' "$LAB_ADDR"
    ;;
  media-port)
    printf '%s\n' "$MEDIA_PORT"
    ;;
  namespace)
    printf '%s\n' "$NS"
    ;;
  lab-interface)
    printf '%s\n' "$LAB_IF"
    ;;
  teardown)
    teardown
    ;;
  assert-clean)
    assert_clean
    ;;
  *)
    echo "unsupported command: $COMMAND" >&2
    exit 2
    ;;
esac
