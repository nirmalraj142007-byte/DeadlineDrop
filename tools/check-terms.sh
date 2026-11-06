#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

HITS=$(grep -RInEi "(commit-reveal|outbox)" \
  --exclude-dir=".git" \
  --exclude-dir="target" \
  --exclude="*check-terms.sh" \
  --exclude="*AGENTS.md" \
  . || true)

if [ -n "$HITS" ]; then
  echo "Banned terms found:"
  echo "$HITS"
  exit 1
else
  echo "terms OK"
  exit 0
fi
