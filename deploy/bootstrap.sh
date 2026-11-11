#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/env.sh
source "${SCRIPT_DIR}/env.sh"

echo "=== Bootstrapping DeadlineDrop Cloud Infrastructure for ${PROJECT_ID} ==="

# 1. Enable required APIs
echo "Enabling GCP APIs..."
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  iamcredentials.googleapis.com \
  cloudscheduler.googleapis.com \
  compute.googleapis.com \
  monitoring.googleapis.com \
  billingbudgets.googleapis.com \
  --project="${PROJECT_ID}"

# 2. Artifact Registry repository
if ! gcloud artifacts repositories describe "${AR_REPO}" --location="${REGION}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "Creating Artifact Registry repository ${AR_REPO}..."
  gcloud artifacts repositories create "${AR_REPO}" \
    --repository-format=docker \
    --location="${REGION}" \
    --project="${PROJECT_ID}" \
    --description="DeadlineDrop docker repository"
else
  echo "Artifact Registry repository ${AR_REPO} already exists."
fi

# 3. Cloud SQL Instance
if ! gcloud sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "Creating Cloud SQL instance ${SQL_INSTANCE} (this may take a few minutes)..."
  gcloud sql instances create "${SQL_INSTANCE}" \
    --database-version=POSTGRES_17 \
    --edition=ENTERPRISE \
    --tier="${SQL_TIER}" \
    --region="${REGION}" \
    --project="${PROJECT_ID}"
else
  echo "Cloud SQL instance ${SQL_INSTANCE} already exists."
fi

# 4. Secrets management (idempotent helper)
create_secret_if_absent() {
  local name="$1"
  local val="$2"
  if ! gcloud secrets describe "$name" --project="${PROJECT_ID}" >/dev/null 2>&1; then
    echo "Creating secret $name..."
    printf '%s' "$val" | gcloud secrets create "$name" --data-file=- --replication-policy="automatic" --project="${PROJECT_ID}"
  else
    echo "Secret $name already exists."
  fi
}

RANDOM_DB_PASS=$(openssl rand -base64 32 | tr -d '\r\n')
create_secret_if_absent "dd-db-password" "$RANDOM_DB_PASS"

RANDOM_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\r\n')
create_secret_if_absent "dd-jwt-secret" "$RANDOM_JWT_SECRET"

RANDOM_SEED_PASS=$(openssl rand -base64 32 | tr -d '\r\n')
create_secret_if_absent "dd-seed-password" "$RANDOM_SEED_PASS"

DB_PASS=$(gcloud secrets versions access latest --secret="dd-db-password" --project="${PROJECT_ID}")

# 5. Cloud SQL user dd
if ! gcloud sql users list --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}" --format="value(name)" | grep -Fxq "dd"; then
  echo "Creating database user dd..."
  gcloud sql users create dd --instance="${SQL_INSTANCE}" --password="${DB_PASS}" --project="${PROJECT_ID}"
else
  echo "Database user dd exists. Ensuring password matches secret..."
  gcloud sql users set-password dd --instance="${SQL_INSTANCE}" --password="${DB_PASS}" --project="${PROJECT_ID}"
fi

# 6. Databases dd_a, dd_b, dd_c
for db in dd_a dd_b dd_c; do
  if ! gcloud sql databases describe "$db" --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
    echo "Creating database $db..."
    gcloud sql databases create "$db" --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}"
  else
    echo "Database $db already exists."
  fi
done

# 7. Cloud Storage bucket
if ! gcloud storage buckets describe "gs://${BUCKET}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "Creating bucket gs://${BUCKET}..."
  gcloud storage buckets create "gs://${BUCKET}" \
    --location="${REGION}" \
    --project="${PROJECT_ID}" \
    --uniform-bucket-level-access \
    --public-access-prevention
else
  echo "Bucket gs://${BUCKET} already exists, verifying settings..."
  gcloud storage buckets update "gs://${BUCKET}" \
    --project="${PROJECT_ID}" \
    --uniform-bucket-level-access \
    --public-access-prevention
fi

# 8. Service Account dd-run
if ! gcloud iam service-accounts describe "${RUN_SA}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "Creating runtime service account dd-run..."
  gcloud iam service-accounts create dd-run \
    --display-name="DeadlineDrop Cloud Run Runtime SA" \
    --project="${PROJECT_ID}"
else
  echo "Runtime service account dd-run already exists."
fi

# Helper for IAM bindings with retry (handles IAM eventual consistency)
add_project_iam_binding_with_retry() {
  local member="$1"
  local role="$2"
  local retries=10
  while [ $retries -gt 0 ]; do
    if gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
      --member="$member" \
      --role="$role" \
      --condition=None >/dev/null 2>&1; then
      return 0
    fi
    echo "Waiting for IAM propagation ($member -> $role)..."
    sleep 4
    retries=$((retries - 1))
  done
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="$member" \
    --role="$role" \
    --condition=None >/dev/null
}

add_sa_iam_binding_with_retry() {
  local target_sa="$1"
  local member="$2"
  local role="$3"
  local retries=10
  while [ $retries -gt 0 ]; do
    if gcloud iam service-accounts add-iam-policy-binding "$target_sa" \
      --member="$member" \
      --role="$role" \
      --project="${PROJECT_ID}" >/dev/null 2>&1; then
      return 0
    fi
    echo "Waiting for IAM propagation ($member -> $target_sa)..."
    sleep 4
    retries=$((retries - 1))
  done
  gcloud iam service-accounts add-iam-policy-binding "$target_sa" \
    --member="$member" \
    --role="$role" \
    --project="${PROJECT_ID}" >/dev/null
}

echo "Configuring IAM roles for dd-run..."
add_project_iam_binding_with_retry "serviceAccount:${RUN_SA}" "roles/cloudsql.client"
add_project_iam_binding_with_retry "serviceAccount:${RUN_SA}" "roles/secretmanager.secretAccessor"

gcloud storage buckets add-iam-policy-binding "gs://${BUCKET}" \
  --member="serviceAccount:${RUN_SA}" \
  --role="roles/storage.objectAdmin" >/dev/null

# Self token-creator permission for IAM signBlob without keys
add_sa_iam_binding_with_retry "${RUN_SA}" "serviceAccount:${RUN_SA}" "roles/iam.serviceAccountTokenCreator"

# 9. Workload Identity Federation & Deployer Service Account
REPO="nirmalraj142007-byte/DeadlineDrop"
POOL_ID="github"
PROVIDER_ID="github-provider"
DEPLOY_SA="dd-deployer@${PROJECT_ID}.iam.gserviceaccount.com"

if ! gcloud iam workload-identity-pools describe "${POOL_ID}" --location="global" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "Creating Workload Identity Pool ${POOL_ID}..."
  gcloud iam workload-identity-pools create "${POOL_ID}" \
    --location="global" \
    --display-name="GitHub Actions Pool" \
    --project="${PROJECT_ID}"
else
  echo "Workload Identity Pool ${POOL_ID} already exists."
fi

if ! gcloud iam workload-identity-pools providers describe "${PROVIDER_ID}" --workload-identity-pool="${POOL_ID}" --location="global" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "Creating Workload Identity Provider ${PROVIDER_ID}..."
  gcloud iam workload-identity-pools providers create-oidc "${PROVIDER_ID}" \
    --workload-identity-pool="${POOL_ID}" \
    --location="global" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner" \
    --attribute-condition="assertion.repository == '${REPO}'" \
    --project="${PROJECT_ID}"
else
  echo "Workload Identity Provider ${PROVIDER_ID} already exists."
fi

if ! gcloud iam service-accounts describe "${DEPLOY_SA}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "Creating deployer service account dd-deployer..."
  gcloud iam service-accounts create dd-deployer \
    --display-name="DeadlineDrop GitHub Deployer SA" \
    --project="${PROJECT_ID}"
else
  echo "Deployer service account dd-deployer already exists."
fi

echo "Configuring IAM roles for dd-deployer..."
add_project_iam_binding_with_retry "serviceAccount:${DEPLOY_SA}" "roles/run.admin"
add_project_iam_binding_with_retry "serviceAccount:${DEPLOY_SA}" "roles/artifactregistry.writer"
add_sa_iam_binding_with_retry "${RUN_SA}" "serviceAccount:${DEPLOY_SA}" "roles/iam.serviceAccountUser"

PROJECT_NUMBER=$(gcloud projects describe "${PROJECT_ID}" --format="value(projectNumber)")
WIF_PRINCIPAL="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL_ID}/attribute.repository/${REPO}"

add_sa_iam_binding_with_retry "${DEPLOY_SA}" "${WIF_PRINCIPAL}" "roles/iam.workloadIdentityUser"

WIF_PROVIDER_RESOURCE="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL_ID}/providers/${PROVIDER_ID}"

echo ""
echo "=== Bootstrap Completed Successfully ==="
echo "GitHub repository variables to set:"
echo "GCP_WIF_PROVIDER=${WIF_PROVIDER_RESOURCE}"
echo "GCP_DEPLOY_SA=${DEPLOY_SA}"
echo "GCP_PROJECT_ID=${PROJECT_ID}"
echo "DEPLOY_ARMS=a c"
echo "========================================="

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  echo "Setting GitHub Actions repository variables via gh CLI..."
  gh variable set GCP_WIF_PROVIDER --body "${WIF_PROVIDER_RESOURCE}" --repo "${REPO}" || true
  gh variable set GCP_DEPLOY_SA --body "${DEPLOY_SA}" --repo "${REPO}" || true
  gh variable set GCP_PROJECT_ID --body "${PROJECT_ID}" --repo "${REPO}" || true
  gh variable set DEPLOY_ARMS --body "a c" --repo "${REPO}" || true
  echo "GitHub Actions variables updated."
fi
