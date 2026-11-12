#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/env.sh
source "${SCRIPT_DIR}/env.sh"
# shellcheck source=deploy/arms.env
source "${SCRIPT_DIR}/arms.env"

if [ "${1:-}" = "seed" ]; then
  ARM="${2:-}"
  N="${3:-50}"
  IMAGE_REF="${4:-${IMAGE}:latest}"

  case "$ARM" in
    a) DB_NAME="dd_a" ;;
    b) DB_NAME="dd_b" ;;
    c) DB_NAME="dd_c" ;;
    *)
      echo "Error: Invalid arm for seed: $ARM. Use a, b, or c." >&2
      exit 1
      ;;
  esac

  JOB_NAME="dd-seed-${ARM}"
  echo "=== Creating or updating Cloud Run job ${JOB_NAME} ==="

  if gcloud run jobs describe "${JOB_NAME}" --region="${REGION}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
    ACTION="update"
  else
    ACTION="create"
  fi

  gcloud run jobs ${ACTION} "${JOB_NAME}" \
    --image="${IMAGE_REF}" \
    --region="${REGION}" \
    --project="${PROJECT_ID}" \
    --service-account="${RUN_SA}" \
    --set-cloudsql-instances="${PROJECT_ID}:${REGION}:${SQL_INSTANCE}" \
    --set-secrets="DB_PASSWORD=dd-db-password:latest,SEED_PASSWORD=dd-seed-password:latest,JWT_SECRET=dd-jwt-secret:latest" \
    --set-env-vars="^@^SPRING_PROFILES_ACTIVE=cloud,seed@DB_NAME=${DB_NAME}@BUCKET=${BUCKET}@CLOUD_SQL_INSTANCE=${PROJECT_ID}:${REGION}:${SQL_INSTANCE}@SEED_STUDENTS=${N}@DB_POOL_MAX=${DB_POOL_MAX}" \
    --max-retries=0 \
    --task-timeout=300

  echo "=== Executing seed job ${JOB_NAME} ==="
  gcloud run jobs execute "${JOB_NAME}" --region="${REGION}" --project="${PROJECT_ID}" --wait
  echo "=== Seed job finished successfully ==="
  exit 0
fi

ARM="${1:-}"
IMAGE_REF="${2:-${IMAGE}:latest}"

case "$ARM" in
  a)
    SPRING_PROFILES_ACTIVE="cloud,naive"
    DB_NAME="dd_a"
    ;;
  b)
    SPRING_PROFILES_ACTIVE="cloud,direct-finalize"
    DB_NAME="dd_b"
    ;;
  c)
    SPRING_PROFILES_ACTIVE="cloud"
    DB_NAME="dd_c"
    ;;
  *)
    echo "Usage: $0 <a|b|c> [image-ref]"
    echo "   or: $0 seed <a|b|c> <N> [image-ref]"
    exit 1
    ;;
esac

SERVICE_NAME="dd-arm-${ARM}"
echo "=== Deploying Cloud Run service ${SERVICE_NAME} ==="

# Arm C runs the transactional job queue: /internal/** accepts Cloud Scheduler's OIDC token, whose
# audience is this service's own URL. Arms A and B set neither variable and deny /internal/** outright.
OIDC_ENV=""
SERVICE_URL=""
if [ "${ARM}" = "c" ]; then
  SERVICE_URL=$(gcloud run services describe "${SERVICE_NAME}" --region="${REGION}" --project="${PROJECT_ID}" \
    --format="value(status.url)" 2>/dev/null || true)
  if [ -n "${SERVICE_URL}" ]; then
    # Joined with "|", the --set-env-vars separator below; "@" cannot be used, the email contains one.
    OIDC_ENV="|INTERNAL_AUDIENCE=${SERVICE_URL}|SCHEDULER_SA_EMAIL=${SCHEDULER_SA}"
  else
    echo "First deploy of ${SERVICE_NAME}: URL unknown until it exists; /internal/** denies all until the update below."
  fi
fi

gcloud run deploy "${SERVICE_NAME}" \
  --image="${IMAGE_REF}" \
  --region="${REGION}" \
  --project="${PROJECT_ID}" \
  --service-account="${RUN_SA}" \
  --add-cloudsql-instances="${PROJECT_ID}:${REGION}:${SQL_INSTANCE}" \
  --set-secrets="JWT_SECRET=dd-jwt-secret:latest,DB_PASSWORD=dd-db-password:latest" \
  --set-env-vars="^|^SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}|DB_NAME=${DB_NAME}|BUCKET=${BUCKET}|CLOUD_SQL_INSTANCE=${PROJECT_ID}:${REGION}:${SQL_INSTANCE}|DB_POOL_MAX=${DB_POOL_MAX}${OIDC_ENV}" \
  --cpu="${CPU}" \
  --memory="${MEMORY}" \
  --concurrency="${CONCURRENCY}" \
  --max-instances="${MAX_INSTANCES}" \
  --min-instances="${MIN_INSTANCES}" \
  --timeout="${TIMEOUT}" \
  --cpu-boost \
  --execution-environment=gen2 \
  --allow-unauthenticated

if [ "${ARM}" = "c" ] && [ -z "${SERVICE_URL}" ]; then
  SERVICE_URL=$(gcloud run services describe "${SERVICE_NAME}" --region="${REGION}" --project="${PROJECT_ID}" --format="value(status.url)")
  echo "=== Setting INTERNAL_AUDIENCE=${SERVICE_URL} on ${SERVICE_NAME} ==="
  gcloud run services update "${SERVICE_NAME}" --region="${REGION}" --project="${PROJECT_ID}" \
    --update-env-vars="INTERNAL_AUDIENCE=${SERVICE_URL},SCHEDULER_SA_EMAIL=${SCHEDULER_SA}"
fi

echo "=== Updating Bucket CORS policy from deployed arm URLs ==="
ORIGINS=$(gcloud run services list --region="${REGION}" --project="${PROJECT_ID}" --filter="metadata.name ~ ^dd-arm-" --format="value(status.url)" | grep -v '^$' || true)

ORIGIN_JSON=""
for url in $ORIGINS; do
  if [ -n "$url" ]; then
    if [ -n "$ORIGIN_JSON" ]; then
      ORIGIN_JSON="${ORIGIN_JSON}, \"${url}\""
    else
      ORIGIN_JSON="\"${url}\""
    fi
  fi
done

if [ -z "$ORIGIN_JSON" ]; then
  ORIGIN_JSON='"*"'
fi

cat <<EOF > "${SCRIPT_DIR}/cors.json"
[
  {
    "origin": [${ORIGIN_JSON}],
    "method": ["PUT", "GET"],
    "responseHeader": ["Content-Type", "Content-Disposition", "x-goog-content-length-range"],
    "maxAgeSeconds": 3600
  }
]
EOF

gcloud storage buckets update "gs://${BUCKET}" --cors-file="${SCRIPT_DIR}/cors.json" --project="${PROJECT_ID}"
echo "Bucket CORS updated."
