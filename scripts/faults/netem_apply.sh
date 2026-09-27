#!/usr/bin/env bash
set -euo pipefail

NS="sponge-m2e-e0"
HOST_IF="m2e0-host"
NS_IF="m2e0-lab"
HOST_CIDR="198.18.0.1/30"
NS_CIDR="198.18.0.2/30"
MEDIA_PORT="18081"
SEED="424242"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "netem_apply.sh must run as root" >&2
    exit 2
  fi
}

ns_exists() {
  ip netns list | awk '{print $1}' | grep -Fxq "$NS"
}

host_if_exists() {
  ip link show dev "$HOST_IF" >/dev/null 2>&1
}

teardown() {
  if ns_exists; then
    mapfile -t pids < <(ip netns pids "$NS" 2>/dev/null || true)
    if (( ${#pids[@]} > 0 )); then
      kill "${pids[@]}" 2>/dev/null || true
      sleep 0.2
      mapfile -t pids < <(ip netns pids "$NS" 2>/dev/null || true)
      if (( ${#pids[@]} > 0 )); then
        kill -9 "${pids[@]}" 2>/dev/null || true
      fi
    fi
    ip netns del "$NS" 2>/dev/null || true
  fi
  if host_if_exists; then
    ip link del "$HOST_IF" 2>/dev/null || true
  fi
}

prepare() {
  teardown
  ip netns add "$NS"
  ip link add "$HOST_IF" type veth peer name "$NS_IF"
  ip link set "$NS_IF" netns "$NS"
  ip address add "$HOST_CIDR" dev "$HOST_IF"
  ip link set "$HOST_IF" up
  ip netns exec "$NS" ip address add "$NS_CIDR" dev "$NS_IF"
  ip netns exec "$NS" ip link set "$NS_IF" up
  ip netns exec "$NS" ip link set lo up
}

remove_fault() {
  if ns_exists; then
    ip netns exec "$NS" tc qdisc del dev "$NS_IF" root 2>/dev/null || true
    ip netns exec "$NS" tc qdisc del dev "$NS_IF" clsact 2>/dev/null || true
  fi
}

apply_fault() {
  ns_exists
  ip netns exec "$NS" tc qdisc replace dev "$NS_IF" root handle 1: netem     delay 2ms 1ms 10% seed "$SEED"
}

inspect_fault() {
  ns_exists
  ip netns exec "$NS" tc -s -j qdisc show dev "$NS_IF"
}

probe_seed() {
  apply_fault
  inspect_fault
  ip netns exec "$NS" tc qdisc del dev "$NS_IF" root
}

probe_filter() {
  ns_exists
  ip netns exec "$NS" tc qdisc add dev "$NS_IF" clsact
  ip netns exec "$NS" tc filter add dev "$NS_IF" egress protocol ip pref 10     flower ip_proto tcp src_port "$MEDIA_PORT" action gact pass
  ip netns exec "$NS" tc -j filter show dev "$NS_IF" egress
  ip netns exec "$NS" tc qdisc del dev "$NS_IF" clsact
}

cleanup_state() {
  local namespace_present=false
  local host_if_present=false
  local netem_present=false
  if ns_exists; then
    namespace_present=true
    if ip netns exec "$NS" tc -j qdisc show dev "$NS_IF" 2>/dev/null |
      grep -q '"kind":"netem"'; then
      netem_present=true
    fi
  fi
  if host_if_exists; then
    host_if_present=true
  fi
  printf '{"schemaVersion":1,"namespacePresent":%s,"hostVethPresent":%s,"netemPresent":%s}\n'     "$namespace_present" "$host_if_present" "$netem_present"
}

require_root
case "${1:-}" in
  prepare)
    prepare
    ;;
  seed-probe)
    probe_seed
    ;;
  filter-probe)
    probe_filter
    ;;
  apply)
    apply_fault
    ;;
  inspect)
    inspect_fault
    ;;
  remove)
    remove_fault
    ;;
  teardown)
    teardown
    ;;
  cleanup-state)
    cleanup_state
    ;;
  *)
    echo "usage: $0 {prepare|seed-probe|filter-probe|apply|inspect|remove|teardown|cleanup-state}" >&2
    exit 2
    ;;
esac
