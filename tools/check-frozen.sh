#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! git rev-parse --verify --quiet 'evidence-frozen^{commit}' >/dev/null; then
  echo "ERROR: evidence-frozen tag is missing; fetch the full repository history." >&2
  exit 1
fi

changed="$(git diff --name-only --no-renames evidence-frozen..HEAD -- \
  'src/main/java/com/deadlinedrop/service/**' \
  'src/main/java/com/deadlinedrop/repository/**' \
  'src/main/java/com/deadlinedrop/jobs/**' \
  'src/main/java/com/deadlinedrop/storage/**' \
  'src/main/java/com/deadlinedrop/web/**' \
  'src/main/resources/db/migration/**' \
  'tools/k6/**' \
  'tools/run.sh' \
  'tools/analyze.py' \
  'tools/gen_schedule.py' \
  'results/runs/**' \
  'results/failure/**' \
  'results/model/**' \
  'PREDICTIONS.md')"

if [[ -n "$changed" ]]; then
  printf '%s\n' "$changed"
  exit 1
fi

echo "Evidence freeze is clean."
