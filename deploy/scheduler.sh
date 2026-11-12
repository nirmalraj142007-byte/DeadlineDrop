#!/usr/bin/env bash
# Cloud Scheduler drives arm C's transactional job queue: every minute it POSTs to the drain and
# reconcile endpoints with a Google-signed OIDC token for the dd-scheduler service account, whose
# audience is arm C's URL. The app accepts exactly that token (InternalSecurityConfig, cloud).
# Idempotent: creates the service account and jobs if missing, otherwise updates the jobs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/env.sh
source "${SCRIPT_DIR}/env.sh"

SERVICE_NAME="dd-arm-c"

echo "=== Service account ${SCHEDULER_SA} ==="
if ! gcloud iam service-accounts describe "${SCHEDULER_SA}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  gcloud iam service-accounts create dd-scheduler \
    --display-name="DeadlineDrop Cloud Scheduler caller" \
    --project="${PROJECT_ID}"
else
  echo "Service account already exists."
fi

URL=$(gcloud run services describe "${SERVICE_NAME}" --region="${REGION}" --project="${PROJECT_ID}" --format="value(status.url)")
if [ -z "${URL}" ]; then
  echo "Error: could not read the URL of ${SERVICE_NAME}; deploy it first (bash deploy/deploy-arm.sh c)." >&2
  exit 1
fi
echo "Arm C URL: ${URL}"

AUDIENCE=$(gcloud run services describe "${SERVICE_NAME}" --region="${REGION}" --project="${PROJECT_ID}" \
  --format=json | python -c "import json,sys; env=json.load(sys.stdin)['spec']['template']['spec']['containers'][0].get('env',[]); print(next((e.get('value','') for e in env if e['name']=='INTERNAL_AUDIENCE'), ''))")
if [ "${AUDIENCE}" != "${URL}" ]; then
  echo "Error: ${SERVICE_NAME} has INTERNAL_AUDIENCE='${AUDIENCE}', expected '${URL}'. Redeploy arm c first." >&2
  exit 1
fi

upsert_job() {
  local name="$1" path="$2"
  local action="create"
  if gcloud scheduler jobs describe "${name}" --location="${REGION}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
    action="update"
  fi
  echo "=== ${action} ${name} -> POST ${URL}${path} ==="
  gcloud scheduler jobs "${action}" http "${name}" \
    --location="${REGION}" \
    --project="${PROJECT_ID}" \
    --schedule="* * * * *" \
    --time-zone="Etc/UTC" \
    --uri="${URL}${path}" \
    --http-method=POST \
    --oidc-service-account-email="${SCHEDULER_SA}" \
    --oidc-token-audience="${URL}" \
    --attempt-deadline=60s
}

upsert_job dd-reconcile-c /internal/reconcile
upsert_job dd-drain-c /internal/jobs/drain

gcloud scheduler jobs list --location="${REGION}" --project="${PROJECT_ID}"
