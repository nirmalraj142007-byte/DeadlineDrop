#!/usr/bin/env bash
# Turn dd-k6-slow into a controller that runs long batches unattended, so the operator's laptop can sleep.
#   bash tools/vm/controller.sh setup      service account (no keys), OS Login, attach SA, install tools, clone repo
#   bash tools/vm/controller.sh grant      the IAM bindings listed in ROLES below (needs the owner's approval)
#   bash tools/vm/controller.sh start      git pull, then tools/vm/phase19-batch.sh as systemd unit "dd-p19"
#   bash tools/vm/controller.sh status     tail of results/remote/progress.log on the controller
#   bash tools/vm/controller.sh fetch      copy the controller's results/ into this checkout
#   bash tools/vm/controller.sh teardown   remove every binding from "grant" and delete the service account
# The controller authenticates as dd-ctl through the metadata server; no key file is ever created.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/common.sh"

CTL_VM="$(vm_name slow)"
CTL_SA_NAME="dd-ctl"
CTL_SA="${CTL_SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
REPO_URL="https://github.com/nirmalraj142007-byte/DeadlineDrop.git"
REMOTE_DIR="DeadlineDrop"
PROXY_VERSION="v2.14.1"
UNIT="dd-p19"

# Project-level roles (no narrower resource exists for these).
PROJECT_ROLES=(
  roles/run.viewer              # describe services/revisions: run.sh, parity.sh, preflight.sh
  roles/cloudsql.client         # cloud-sql-proxy for preflight; sql instances describe for meta.json
  roles/monitoring.viewer       # coldstart.sh reads instance_count
  roles/cloudscheduler.admin    # coldstart.sh pauses/resumes dd-drain-c, dd-reconcile-c
  roles/compute.viewer          # gcloud compute ssh/scp look up the VMs
)

ensure_config_cmd="gcloud config configurations describe deadlinedrop >/dev/null 2>&1 || gcloud config configurations create deadlinedrop --no-activate; \
gcloud config set project ${PROJECT_ID} --configuration=deadlinedrop; \
gcloud config set compute/zone ${ZONE} --configuration=deadlinedrop"

each_binding() { # each_binding add|remove
  local verb="$1" r
  for r in "${PROJECT_ROLES[@]}"; do
    gcloud projects "${verb}-iam-policy-binding" "${PROJECT_ID}" --member="serviceAccount:${CTL_SA}" \
      --role="${r}" --condition=None --quiet >/dev/null
    echo "  ${verb} ${r} (project)"
  done
  gcloud run services "${verb}-iam-policy-binding" dd-arm-c --region="${REGION}" --project="${PROJECT_ID}" \
    --member="serviceAccount:${CTL_SA}" --role=roles/run.developer --quiet >/dev/null
  echo "  ${verb} roles/run.developer on service dd-arm-c"
  gcloud iam service-accounts "${verb}-iam-policy-binding" "${RUN_SA}" --project="${PROJECT_ID}" \
    --member="serviceAccount:${CTL_SA}" --role=roles/iam.serviceAccountUser --quiet >/dev/null
  echo "  ${verb} roles/iam.serviceAccountUser on ${RUN_SA}"
  gcloud iam service-accounts "${verb}-iam-policy-binding" "${CTL_SA}" --project="${PROJECT_ID}" \
    --member="serviceAccount:${CTL_SA}" --role=roles/iam.serviceAccountUser --quiet >/dev/null
  echo "  ${verb} roles/iam.serviceAccountUser on ${CTL_SA} (OS Login into ${CTL_VM}, which runs as it)"
  for s in dd-seed-password dd-db-password; do
    gcloud secrets "${verb}-iam-policy-binding" "${s}" --project="${PROJECT_ID}" \
      --member="serviceAccount:${CTL_SA}" --role=roles/secretmanager.secretAccessor --quiet >/dev/null
    echo "  ${verb} roles/secretmanager.secretAccessor on secret ${s}"
  done
  gcloud storage buckets "${verb}-iam-policy-binding" "gs://${BUCKET}" \
    --member="serviceAccount:${CTL_SA}" --role=roles/storage.objectAdmin --quiet >/dev/null
  echo "  ${verb} roles/storage.objectAdmin on gs://${BUCKET}"
  for c in "${COHORTS[@]}"; do
    gcloud compute instances "${verb}-iam-policy-binding" "$(vm_name "${c}")" --zone="${ZONE}" --project="${PROJECT_ID}" \
      --member="serviceAccount:${CTL_SA}" --role=roles/compute.osAdminLogin --quiet >/dev/null
    echo "  ${verb} roles/compute.osAdminLogin on instance $(vm_name "${c}")"
  done
}

case "${1:-}" in
  setup)
    if ! gcloud iam service-accounts describe "${CTL_SA}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
      gcloud iam service-accounts create "${CTL_SA_NAME}" --project="${PROJECT_ID}" \
        --display-name="DeadlineDrop phase-19 controller (no keys; delete after the batch)"
    fi
    for c in "${COHORTS[@]}"; do
      gcloud compute instances add-metadata "$(vm_name "${c}")" --zone="${ZONE}" --project="${PROJECT_ID}" \
        --metadata=enable-oslogin=TRUE >/dev/null
    done
    echo "OS Login enabled on the fleet"
    current="$(gv compute instances describe "${CTL_VM}" --zone="${ZONE}" --project="${PROJECT_ID}" --format='value(serviceAccounts[0].email)')"
    if [ "${current}" != "${CTL_SA}" ]; then
      gcloud compute instances stop "${CTL_VM}" --zone="${ZONE}" --project="${PROJECT_ID}" --quiet
      gcloud compute instances set-service-account "${CTL_VM}" --zone="${ZONE}" --project="${PROJECT_ID}" \
        --service-account="${CTL_SA}" --scopes=cloud-platform
      gcloud compute instances start "${CTL_VM}" --zone="${ZONE}" --project="${PROJECT_ID}"
      for _ in $(seq 1 60); do vm_ssh "${CTL_VM}" "test -f /run/dd-k6-ready" >/dev/null 2>&1 && break; sleep 10; done
    fi
    vm_ssh "${CTL_VM}" "sudo DEBIAN_FRONTEND=noninteractive apt-get -qq update && \
      sudo DEBIAN_FRONTEND=noninteractive apt-get -qq install -y git tmux jq python3 python3-numpy python3-pytest postgresql-client >/dev/null && \
      (command -v cloud-sql-proxy >/dev/null || (sudo curl -fsSL -o /usr/local/bin/cloud-sql-proxy \
        https://storage.googleapis.com/cloud-sql-connectors/cloud-sql-proxy/${PROXY_VERSION}/cloud-sql-proxy.linux.amd64 && \
        sudo chmod 755 /usr/local/bin/cloud-sql-proxy)) && \
      (test -d ~/${REMOTE_DIR} || git clone -q ${REPO_URL} ~/${REMOTE_DIR}) && \
      ${ensure_config_cmd} >/dev/null 2>&1 && \
      echo tools: \$(git --version) \$(jq --version) \$(python3 --version) \$(psql --version) \$(cloud-sql-proxy --version | head -1) && \
      echo gcloud identity: \$(gcloud auth list --filter=status:ACTIVE --format='value(account)')"
    ;;
  grant)    each_binding add ;;
  start)
    # A system unit, not tmux: on this image sshd logins get no logind session (pam_systemd "Failed to
    # create session"), so anything started over SSH lives in sshd's cgroup; a tmux batch died that way.
    vm_ssh "${CTL_VM}" "cd ~/${REMOTE_DIR} && git pull -q --ff-only && git log --oneline -1 && \
      python3 -m pytest -q -p no:cacheprovider tools/tests 2>&1 | tail -1 && \
      sudo systemd-run --collect --unit=${UNIT} --uid=\$(id -u) --gid=\$(id -g) \
        --setenv=HOME=\$HOME --setenv=PATH=\$PATH --working-directory=\$PWD \
        bash tools/vm/phase19-batch.sh && echo unit ${UNIT} started"
    ;;
  status)   vm_ssh "${CTL_VM}" "tail -n ${2:-25} ~/${REMOTE_DIR}/results/remote/progress.log; echo \"(batch unit ${UNIT}: \$(systemctl is-active ${UNIT}))\"" ;;
  fetch)
    vm_ssh "${CTL_VM}" "cd ~/${REMOTE_DIR} && tar czf ~/results.tgz results"
    vm_scp "${CTL_VM}:results.tgz" "${REPO_ROOT}/results-remote.tgz"
    tar xzf "${REPO_ROOT}/results-remote.tgz" -C "${REPO_ROOT}" && rm -f "${REPO_ROOT}/results-remote.tgz"
    echo "results/ merged from ${CTL_VM}"
    ;;
  teardown)
    each_binding remove || true
    gcloud iam service-accounts delete "${CTL_SA}" --project="${PROJECT_ID}" --quiet
    ;;
  *) echo "Usage: $0 setup|grant|start|status|fetch|teardown" >&2; exit 1 ;;
esac
