#!/usr/bin/env bash
# Phase 40: delete every billable DeadlineDrop resource in project deadlinedrop-nirmal.
#
#   bash tools/shutdown.sh                      list every resource each group would delete (no changes)
#   bash tools/shutdown.sh --confirm <group>    print what <group> deletes, read the word "delete" on
#                                               stdin, then delete that group only
#   bash tools/shutdown.sh --confirm all        the same for every group in order, one typed "delete" each
#
# Groups, in the only order allowed (a group refuses to run while an earlier group still has resources,
# so nothing is deleted while something that uses it is still running):
#   scheduler  Cloud Scheduler jobs in asia-south1
#   run        Cloud Run services and jobs in asia-south1
#   sql        Cloud SQL instance dd-pg (all three arm databases with it)
#   bucket     every object in the bucket, then the bucket
#   ar         Artifact Registry repository dd (all images)
#   vms        Compute Engine VMs named dd-* (the k6 fleet)
#   secrets    secrets dd-* (all versions, dd-telemetry-salt included)
#   sa         service accounts dd-run, dd-deployer, dd-scheduler
#   wif        Workload Identity pool github (and its provider)
# After the last group it disables the GitHub Deploy workflow so no push can rebuild anything.
# The budget alert dd-40 is never touched. Deletion cannot be undone; take the backups first
# (results/backup/, results/cloud-inventory/).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=deploy/env.sh
source "${REPO_ROOT}/deploy/env.sh"

if [ "${PROJECT_ID}" != "deadlinedrop-nirmal" ]; then
  echo "Refusing: gcloud project is '${PROJECT_ID}', expected deadlinedrop-nirmal." >&2
  exit 1
fi

GROUPS_IN_ORDER=(scheduler run sql bucket ar vms secrets sa wif)
SERVICE_ACCOUNTS=(dd-run dd-deployer dd-scheduler)
POOL_ID="github"

# tr: gcloud on Windows ends lines with CRLF, and a trailing \r breaks the names passed back to delete.
g() { gcloud --project="${PROJECT_ID}" --quiet "$@" 2>/dev/null | tr -d '\r'; }

# Each list_* prints one resource per line, nothing when the group is empty.
list_scheduler() { g scheduler jobs list --location="${REGION}" --format="value(name.basename())"; }
list_run() {
  g run services list --region="${REGION}" --format="value(metadata.name)" | sed 's/^/service /'
  g run jobs list --region="${REGION}" --format="value(metadata.name)" | sed 's/^/job /'
}
list_sql() { g sql instances list --filter="name=${SQL_INSTANCE}" --format="value(name)"; }
list_bucket() {
  if g storage buckets describe "gs://${BUCKET}" --format="value(name)" >/dev/null; then
    local count
    count="$(g storage ls -r "gs://${BUCKET}/**" | grep -c '^gs://' || true)"
    echo "gs://${BUCKET} (${count} objects)"
  fi
}
list_ar() { g artifacts repositories list --location="${REGION}" --filter="name~/repositories/${AR_REPO}$" --format="value(name.basename())"; }
list_vms() { g compute instances list --filter="name~^dd-" --format="value(name,zone.basename())"; }
list_secrets() { g secrets list --filter="name~/secrets/dd-" --format="value(name.basename())"; }
list_sa() {
  local sa
  for sa in "${SERVICE_ACCOUNTS[@]}"; do
    g iam service-accounts describe "${sa}@${PROJECT_ID}.iam.gserviceaccount.com" --format="value(email)" || true
  done
}
list_wif() { g iam workload-identity-pools list --location=global --filter="name~/${POOL_ID}$ AND state=ACTIVE" --format="value(name.basename())"; }

delete_scheduler() {
  local job
  for job in $(list_scheduler); do
    gcloud scheduler jobs delete "${job}" --location="${REGION}" --project="${PROJECT_ID}" --quiet
  done
}
delete_run() {
  local kind name
  while read -r kind name; do
    [ -n "${name}" ] || continue
    gcloud run "${kind}s" delete "${name}" --region="${REGION}" --project="${PROJECT_ID}" --quiet
  done < <(list_run)
}
delete_sql() {
  gcloud sql instances delete "${SQL_INSTANCE}" --project="${PROJECT_ID}" --quiet
}
delete_bucket() {
  # Removes every object (the invalid-run leftovers and the demo Lab 4 files included), then the bucket.
  gcloud storage rm -r "gs://${BUCKET}" --project="${PROJECT_ID}" --quiet
}
delete_ar() {
  gcloud artifacts repositories delete "${AR_REPO}" --location="${REGION}" --project="${PROJECT_ID}" --quiet
}
delete_vms() {
  local name zone
  while read -r name zone; do
    [ -n "${name}" ] || continue
    gcloud compute instances delete "${name}" --zone="${zone}" --project="${PROJECT_ID}" --delete-disks=all --quiet
  done < <(list_vms)
}
delete_secrets() {
  local secret
  for secret in $(list_secrets); do
    gcloud secrets delete "${secret}" --project="${PROJECT_ID}" --quiet
  done
}
delete_sa() {
  local sa
  for sa in $(list_sa); do
    gcloud iam service-accounts delete "${sa}" --project="${PROJECT_ID}" --quiet
  done
}
delete_wif() {
  gcloud iam workload-identity-pools delete "${POOL_ID}" --location=global --project="${PROJECT_ID}" --quiet
}

show_group() {
  local group="$1" items
  items="$("list_${group}")"
  if [ -z "${items}" ]; then
    echo "[${group}] nothing to delete"
  else
    echo "[${group}] would delete:"
    sed 's/^/  /' <<<"${items}"
  fi
}

run_group() {
  local group="$1" earlier items answer
  for earlier in "${GROUPS_IN_ORDER[@]}"; do
    [ "${earlier}" = "${group}" ] && break
    if [ -n "$("list_${earlier}")" ]; then
      echo "Refusing [${group}]: earlier group [${earlier}] still has resources. Delete in order." >&2
      exit 1
    fi
  done
  items="$("list_${group}")"
  if [ -z "${items}" ]; then
    echo "[${group}] nothing to delete"
    return
  fi
  echo "[${group}] will PERMANENTLY delete (cannot be undone):"
  sed 's/^/  /' <<<"${items}"
  printf 'Type delete to continue: '
  read -r answer || answer=""
  if [ "${answer}" != "delete" ]; then
    echo "Not confirmed; [${group}] left as it is." >&2
    exit 1
  fi
  "delete_${group}"
  if [ -n "$("list_${group}")" ]; then
    echo "[${group}] still lists resources after delete:" >&2
    "list_${group}" >&2
    exit 1
  fi
  echo "[${group}] deleted"
}

disable_deploy_workflow() {
  if command -v gh >/dev/null 2>&1; then
    gh workflow disable deploy.yml 2>/dev/null || true
    gh workflow list --all | grep -E '^Deploy' || true
  else
    echo "gh not found: disable the Deploy workflow by hand (gh workflow disable deploy.yml)." >&2
  fi
}

if [ "${1:-}" != "--confirm" ]; then
  echo "Dry run for project ${PROJECT_ID} (nothing is deleted). Budget alert dd-40 is kept."
  for group in "${GROUPS_IN_ORDER[@]}"; do show_group "${group}"; done
  echo "To delete: bash tools/shutdown.sh --confirm <$(IFS='|'; echo "${GROUPS_IN_ORDER[*]}")|all>"
  exit 0
fi

TARGET="${2:-}"
if [ "${TARGET}" = "all" ]; then
  for group in "${GROUPS_IN_ORDER[@]}"; do run_group "${group}"; done
  disable_deploy_workflow
  exit 0
fi
for group in "${GROUPS_IN_ORDER[@]}"; do
  if [ "${group}" = "${TARGET}" ]; then
    run_group "${group}"
    [ "${group}" = "wif" ] && disable_deploy_workflow
    exit 0
  fi
done
echo "Usage: $0 [--confirm <$(IFS='|'; echo "${GROUPS_IN_ORDER[*]}")|all>]" >&2
exit 1
