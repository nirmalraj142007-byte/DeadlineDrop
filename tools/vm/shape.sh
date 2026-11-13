#!/usr/bin/env bash
# Shape the primary interface's EGRESS to a cohort profile. Run on a k6 VM as root:
#   sudo bash shape.sh <slow|medium|fast|clear>
# netem (root qdisc) adds RTT delay and loss; an fq child qdisc caps the rate per flow.
# If fq cannot attach under netem on this kernel, stop: docs/decisions.md U8 says the fallback
# is HTB classes per source port.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILE="${1:-}"
case "${PROFILE}" in
  slow|medium|fast|clear) ;;
  *) echo "Usage: $0 <slow|medium|fast|clear>" >&2; exit 1 ;;
esac

if [ "$(id -u)" -ne 0 ]; then
  echo "Error: shape.sh must run as root (use sudo)." >&2
  exit 1
fi

IF="$(ip -o route get 8.8.8.8 | sed -n 's/.* dev \([^ ]*\).*/\1/p' | head -n 1)"
if [ -z "${IF}" ]; then
  echo "Error: could not determine the primary network interface." >&2
  exit 1
fi

if [ "${PROFILE}" = "clear" ]; then
  tc qdisc del dev "${IF}" root 2>/dev/null || true
  echo "Cleared shaping on ${IF}."
  tc qdisc show dev "${IF}"
  exit 0
fi

# shellcheck source=tools/vm/cohorts.env
source "${SCRIPT_DIR}/cohorts.env"
upper="$(echo "${PROFILE}" | tr '[:lower:]' '[:upper:]')"
rate_var="${upper}_RATE"; delay_var="${upper}_DELAY"; loss_var="${upper}_LOSS"
RATE="${!rate_var:?${rate_var} missing in cohorts.env}"
DELAY="${!delay_var:?${delay_var} missing in cohorts.env}"
LOSS="${!loss_var:?${loss_var} missing in cohorts.env}"

modprobe sch_netem 2>/dev/null || true
modprobe sch_fq 2>/dev/null || true

tc qdisc replace dev "${IF}" root handle 1: netem delay "${DELAY}" loss "${LOSS}" limit 100000
if ! tc qdisc replace dev "${IF}" parent 1:1 handle 10: fq maxrate "${RATE}"; then
  echo "ERROR: fq cannot attach under netem on this kernel ($(uname -r))." >&2
  echo "Stop and report: docs/decisions.md U8 fallback is HTB classes per source port." >&2
  tc qdisc del dev "${IF}" root 2>/dev/null || true
  exit 2
fi

echo "Shaping ${IF} as '${PROFILE}': rate ${RATE}, delay ${DELAY}, loss ${LOSS}"
tc qdisc show dev "${IF}"
