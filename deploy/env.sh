#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null | tail -n 1 || echo "")}"
if [ -z "${PROJECT_ID}" ] || [ "${PROJECT_ID}" = "(unset)" ]; then
  echo "Error: No project configured in gcloud. Run 'gcloud config set project <PROJECT_ID>'." >&2
  exit 1
fi
export PROJECT_ID

export REGION="asia-south1"
export ZONE="asia-south1-a"
export SQL_INSTANCE="dd-pg"
export SQL_TIER="db-f1-micro"
export BUCKET="${PROJECT_ID}-dd-objects"
export AR_REPO="dd"
export IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}/app"
export RUN_SA="dd-run@${PROJECT_ID}.iam.gserviceaccount.com"
