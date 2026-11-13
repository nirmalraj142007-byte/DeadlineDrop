#!/usr/bin/env bash
# VM startup script for the k6 load-generator fleet (Debian 12). Runs as root at every boot.
# Installs the pinned k6 release, python3, jq, and makes chrony follow the Google metadata NTP
# server. Touches /run/dd-k6-ready only when k6 is installed AND the clock is synchronised.
set -euo pipefail

# Keep in sync with the k6 pin in tools/README.md.
K6_VERSION="v2.3.0"
READY_FILE="/run/dd-k6-ready"
SYNC_TIMEOUT_S=300

exec > >(tee -a /var/log/dd-startup.log) 2>&1
echo "=== dd startup $(date -u +%FT%TZ) ==="
rm -f "${READY_FILE}"

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y --no-install-recommends chrony python3 jq curl ca-certificates iproute2 tar

# --- k6 (pinned GitHub tarball, checksum verified) ---
if ! k6 version 2>/dev/null | grep -q "k6 ${K6_VERSION} "; then
  tmp="$(mktemp -d)"
  base="https://github.com/grafana/k6/releases/download/${K6_VERSION}"
  tarball="k6-${K6_VERSION}-linux-amd64.tar.gz"
  curl -fsSL --retry 5 --max-time 120 -o "${tmp}/${tarball}" "${base}/${tarball}"
  curl -fsSL --retry 5 --max-time 60 -o "${tmp}/checksums.txt" "${base}/k6-${K6_VERSION}-checksums.txt"
  (cd "${tmp}" && grep " ${tarball}\$" checksums.txt | sha256sum -c -)
  tar -xzf "${tmp}/${tarball}" -C "${tmp}"
  install -m 0755 "${tmp}/k6-${K6_VERSION}-linux-amd64/k6" /usr/local/bin/k6
  rm -rf "${tmp}"
fi
k6 version

# --- chrony: the metadata server is Google's NTP source inside the VPC ---
cat > /etc/chrony/chrony.conf <<'EOF'
server metadata.google.internal prefer iburst minpoll 4 maxpoll 4
driftfile /var/lib/chrony/chrony.drift
makestep 1.0 -1
rtcsync
EOF
systemctl enable chrony
systemctl restart chrony

echo "Waiting up to ${SYNC_TIMEOUT_S}s for chrony to report a synchronised clock..."
deadline=$(( $(date +%s) + SYNC_TIMEOUT_S ))
synced=0
while [ "$(date +%s)" -lt "${deadline}" ]; do
  tracking="$(chronyc tracking 2>/dev/null || true)"
  ref="$(echo "${tracking}" | awk -F': ' '/^Reference ID/ {print $2}' | awk '{print $1}')"
  leap="$(echo "${tracking}" | awk -F': ' '/^Leap status/ {print $2}')"
  if [ -n "${ref}" ] && [ "${ref}" != "00000000" ] && [ "${leap}" = "Normal" ]; then
    synced=1
    break
  fi
  sleep 3
done

chronyc tracking || true
if [ "${synced}" -ne 1 ]; then
  echo "ERROR: chrony did not synchronise within ${SYNC_TIMEOUT_S}s; VM is NOT ready." >&2
  exit 1
fi

date -u +%FT%TZ > "${READY_FILE}"
echo "=== dd startup complete: k6 ${K6_VERSION}, clock synchronised ==="
