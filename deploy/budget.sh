#!/usr/bin/env bash
set -euo pipefail

# BUDGET_AMOUNT must be specified in the billing account's currency (e.g. 3500INR).
# The display name dd-40 signifies roughly US$40.

if [ -z "${BILLING_ACCOUNT:-}" ]; then
  echo "Error: BILLING_ACCOUNT environment variable is required." >&2
  exit 1
fi

if [ -z "${BUDGET_AMOUNT:-}" ]; then
  echo "Error: BUDGET_AMOUNT environment variable is required (e.g. 3500INR; must match billing account currency; dd-40 represents roughly US$40)." >&2
  exit 1
fi

gcloud billing budgets create \
  --billing-account="$BILLING_ACCOUNT" \
  --display-name=dd-40 \
  --budget-amount="$BUDGET_AMOUNT" \
  --threshold-rule=percent=0.5 \
  --threshold-rule=percent=0.9 \
  --threshold-rule=percent=1.0
