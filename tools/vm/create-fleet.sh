#!/usr/bin/env bash
# Create, inspect or delete the three k6 load-generator VMs.
#   bash tools/vm/create-fleet.sh [create|delete|status]
# VMs cost money: delete them as soon as a run is finished.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/common.sh"

ACTION="${1:-status}"
READY_TIMEOUT_S=600

# Sum of guestCpus over the cohorts' machine types (they differ per cohort; see vm_machine_type).
# Prints "<cohort>:<type>:<vcpu>" lines to stderr and the total to stdout.
fleet_vcpus() {
  local c type cpus total=0
  for c in "${COHORTS[@]}"; do
    type="$(vm_machine_type "${c}")" || exit 1
    cpus="$(gv compute machine-types describe "${type}" --zone="${ZONE}" --project="${PROJECT_ID}" --format='value(guestCpus)')"
    case "${cpus}" in ''|*[!0-9]*) echo "Error: could not read guestCpus of ${type}" >&2; exit 1 ;; esac
    echo "  $(vm_name "${c}"): ${type}, ${cpus} vCPU" >&2
    total=$(( total + cpus ))
  done
  echo "${total}"
}

assert_vcpu_cap() {
  local total
  total="$(fleet_vcpus)"
  echo "Fleet vCPU total: ${total} (cap ${MAX_FLEET_VCPU}, free-trial limit)"
  if [ "${total}" -gt "${MAX_FLEET_VCPU}" ]; then
    echo "Error: ${total} vCPU exceeds the ${MAX_FLEET_VCPU} vCPU free-trial cap (review G9.5)." >&2
    exit 1
  fi
}

wait_ready() {
  local vm="$1" deadline
  deadline=$(( $(date +%s) + READY_TIMEOUT_S ))
  while [ "$(date +%s)" -lt "${deadline}" ]; do
    if vm_ssh "${vm}" "test -f /run/dd-k6-ready" >/dev/null 2>&1; then
      echo "${vm}: ready (k6 installed, clock synchronised)"
      return 0
    fi
    sleep 10
  done
  echo "Error: ${vm} not ready after ${READY_TIMEOUT_S}s. Check: gcloud compute ssh ${vm} -- sudo tail /var/log/dd-startup.log" >&2
  return 1
}

case "${ACTION}" in
  create)
    assert_vcpu_cap
    for c in "${COHORTS[@]}"; do
      vm="$(vm_name "${c}")"
      want_type="$(vm_machine_type "${c}")"
      if have_type="$(gv compute instances describe "${vm}" --zone="${ZONE}" --project="${PROJECT_ID}" --format='value(machineType.basename())' 2>/dev/null)"; then
        if [ "${have_type}" != "${want_type}" ]; then
          echo "Error: ${vm} exists as ${have_type}, expected ${want_type}. Delete the fleet first (create-fleet.sh delete)." >&2
          exit 1
        fi
        echo "${vm}: already exists (${have_type}), skipping create"
        continue
      fi
      # No service account and no scopes: the load generators never call a Google API.
      gcloud compute instances create "${vm}" \
        --zone="${ZONE}" --project="${PROJECT_ID}" \
        --machine-type="${want_type}" \
        --image-family="${VM_IMAGE_FAMILY}" --image-project="${VM_IMAGE_PROJECT}" \
        --no-service-account --no-scopes \
        --labels=app=deadlinedrop,role=k6,cohort="${c}" \
        --metadata-from-file=startup-script="${SCRIPT_DIR}/startup.sh"
    done
    for c in "${COHORTS[@]}"; do
      vm="$(vm_name "${c}")"
      wait_ready "${vm}"
      vm_ssh "${vm}" "mkdir -p ~/${VM_HOME_DIR}" >/dev/null
      vm_scp "${SCRIPT_DIR}/shape.sh" "${SCRIPT_DIR}/cohorts.env" "${vm}:${VM_HOME_DIR}/"
    done
    echo "Fleet ready. shape.sh and cohorts.env are in ~/${VM_HOME_DIR}/ on each VM."
    ;;
  delete)
    existing=()
    for c in "${COHORTS[@]}"; do
      vm="$(vm_name "${c}")"
      if gcloud compute instances describe "${vm}" --zone="${ZONE}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
        existing+=("${vm}")
      fi
    done
    if [ "${#existing[@]}" -eq 0 ]; then
      echo "No fleet VMs exist."
      exit 0
    fi
    gcloud compute instances delete "${existing[@]}" --zone="${ZONE}" --project="${PROJECT_ID}" --quiet
    echo "Deleted: ${existing[*]}"
    ;;
  status)
    echo "Fleet vCPU total if created: $(fleet_vcpus) (cap ${MAX_FLEET_VCPU})"
    gcloud compute instances list --project="${PROJECT_ID}" --filter="name~^dd-k6-" \
      --format="table(name,zone.basename(),machineType.basename(),status,networkInterfaces[0].accessConfigs[0].natIP:label=EXTERNAL_IP)"
    ;;
  *)
    echo "Usage: $0 [create|delete|status]" >&2
    exit 1
    ;;
esac
