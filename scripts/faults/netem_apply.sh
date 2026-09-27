#!/usr/bin/env bash
set -euo pipefail

NS="sponge-m2e-e0"
HOST_IF="m2e0-host"
NS_IF="m2e0-lab"
HOST_CIDR="198.18.0.1/30"
NS_CIDR="198.18.0.2/30"
MEDIA_PORT="18081"
SEED="424242"
TC_BIN="${SPONGE_TC_BIN:-tc}"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "netem_apply.sh must run as root" >&2
    exit 2
  fi
  if [[ "$TC_BIN" == */* ]]; then
    [[ -x "$TC_BIN" ]] || {
      echo "SPONGE_TC_BIN is not executable: $TC_BIN" >&2
      exit 2
    }
  else
    command -v "$TC_BIN" >/dev/null 2>&1 || {
      echo "tc executable not found: $TC_BIN" >&2
      exit 2
    }
  fi
}

tc_ns() {
  ip netns exec "$NS" "$TC_BIN" "$@"
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
    tc_ns qdisc del dev "$NS_IF" root 2>/dev/null || true
    tc_ns qdisc del dev "$NS_IF" clsact 2>/dev/null || true
  fi
}

apply_fault() {
  ns_exists
  # E0 is topology/capability proof, not an acceptance impairment profile.
  # A fixed delay drives packets through netem while the explicit seed proves
  # the kernel/userspace seed contract without introducing random packet loss.
  tc_ns qdisc replace dev "$NS_IF" root handle 1: netem     delay 2ms seed "$SEED"
}

inspect_fault() {
  ns_exists
  tc_ns -s -j qdisc show dev "$NS_IF"
}

probe_seed() {
  ns_exists
  tc_ns qdisc replace dev "$NS_IF" root handle 1: netem     loss random 1% seed "$SEED"
  inspect_fault
  tc_ns qdisc del dev "$NS_IF" root
}

probe_filter() {
  ns_exists
  tc_ns qdisc add dev "$NS_IF" clsact
  tc_ns filter add dev "$NS_IF" egress protocol ip pref 10     flower ip_proto tcp src_port "$MEDIA_PORT" action gact pass
  tc_ns -j filter show dev "$NS_IF" egress
  tc_ns qdisc del dev "$NS_IF" clsact
}

cleanup_state() {
  local namespace_present=false
  local host_if_present=false
  local netem_present=false
  if ns_exists; then
    namespace_present=true
    if tc_ns -j qdisc show dev "$NS_IF" 2>/dev/null |
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
