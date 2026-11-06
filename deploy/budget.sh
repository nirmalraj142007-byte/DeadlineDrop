#!/usr/bin/env bash
set -euo pipefail

if [ -z "${BILLING_ACCOUNT:-}" ]; then
  echo "Error: BILLING_ACCOUNT environment variable is required." >&2
  exit 1
fi

gcloud billing budgets create \
  --billing-account="$BILLING_ACCOUNT" \
  --display-name=dd-40 \
  --budget-amount=40USD \
  --threshold-rule=percent=0.5 \
  --threshold-rule=percent=0.9 \
  --threshold-rule=percent=1.0
