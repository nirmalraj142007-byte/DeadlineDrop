#!/usr/bin/env bash
# Shared helpers for the k6 VM fleet scripts. Source it; do not run it.
# Requires Git Bash (or any POSIX shell) with gcloud on PATH.

VM_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${VM_COMMON_DIR}/../.." && pwd)"

# The project uses the "deadlinedrop" gcloud configuration (project deadlinedrop-nirmal).
export CLOUDSDK_ACTIVE_CONFIG_NAME="${CLOUDSDK_ACTIVE_CONFIG_NAME:-deadlinedrop}"
# shellcheck source=deploy/env.sh
source "${REPO_ROOT}/deploy/env.sh"

COHORTS=(slow medium fast)
VM_MACHINE_TYPE="e2-standard-2"
VM_IMAGE_FAMILY="debian-12"
VM_IMAGE_PROJECT="debian-cloud"
MAX_FLEET_VCPU=8
# Directory on each VM that receives shape.sh, cohorts.env and the run files.
VM_HOME_DIR="dd"

vm_name() { echo "dd-k6-$1"; }

# Locate a Python 3 that actually runs (the Windows "python3" alias is a Store stub).
find_python() {
  local c
  for c in python python3 py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1; then
      echo "$c"
      return 0
    fi
  done
  echo "Error: no working Python 3 found on PATH." >&2
  return 1
}

# Path to hand to native Windows programs (python, k6); identity elsewhere.
to_native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi
}

# gcloud prints CRLF on Windows; strip it from captured values.
gv() { gcloud "$@" | tr -d '\r'; }

# Every SSH/SCP call has a time limit: on the operator's link one pscp hung 25 minutes before a
# "Connection reset by peer" (phase 19). VM_CALL_TIMEOUT_S covers the longest call (sanity's 20 MB
# upload at 1 Mbit/s, about 3 minutes).
VM_CALL_TIMEOUT_S="${VM_CALL_TIMEOUT_S:-600}"

vm_ssh() {
  local vm="$1"; shift
  timeout "${VM_CALL_TIMEOUT_S}" gcloud compute ssh "${vm}" --zone="${ZONE}" --project="${PROJECT_ID}" --quiet \
    --strict-host-key-checking=no --command="$*"
}

vm_scp() {
  timeout "${VM_CALL_TIMEOUT_S}" gcloud compute scp --zone="${ZONE}" --project="${PROJECT_ID}" --quiet \
    --strict-host-key-checking=no "$@"
}

# retry <command...>: up to 3 attempts, 10 s apart. Only for calls that are safe to repeat.
retry() {
  local n rc
  for n in 1 2 3; do
    "$@" && return 0
    rc=$?
    [ "${n}" -lt 3 ] && { echo "retry ${n}/3 after exit ${rc}: $1 $2" >&2; sleep 10; }
  done
  return "${rc}"
}

# Copy a secret to a file on a VM (mode 600). Never pipe it into vm_ssh: on Windows gcloud runs
# PuTTY plink and writes "y" to its stdin to accept the host key, replacing what was piped in.
vm_put_secret() { # vm_put_secret <vm> <path relative to the VM home> <value>
  local vm="$1" dest="$2" value="$3" tmp rc=0
  tmp="$(mktemp)"
  chmod 600 "${tmp}"
  printf '%s' "${value}" > "${tmp}"
  vm_scp "${tmp}" "${vm}:${dest}" >/dev/null || rc=$?
  rm -f "${tmp}"
  [ "${rc}" -eq 0 ] || return "${rc}"
  vm_ssh "${vm}" "chmod 600 ~/${dest}" >/dev/null
}

# URL of one arm's Cloud Run service.
arm_url() {
  gv run services describe "dd-arm-$1" --region="${REGION}" --project="${PROJECT_ID}" --format="value(status.url)"
}
