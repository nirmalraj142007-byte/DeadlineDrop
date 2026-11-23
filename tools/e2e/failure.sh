#!/usr/bin/env bash
# Failure path: 5 students click while Postgres is paused for 10 s, then unpaused.
# Needs the stack from `docker compose up` (run it through tools/e2e/e2e.sh) and PASSWORD (the seed password).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
: "${PASSWORD:?PASSWORD (the seed password) must be set}"

LOG="${E2E_LOG_DIR:-target/e2e}/failure.log"
mkdir -p "$(dirname "$LOG")"
: > "$LOG"

paused=0
resume() { if [ "$paused" = 1 ]; then docker compose unpause postgres >/dev/null 2>&1 || true; paused=0; fi; }
trap resume EXIT

k6 run -e PASSWORD="$PASSWORD" -e BASE_URL="${BASE_URL:-http://localhost:8080}" tools/e2e/failure.js >"$LOG" 2>&1 &
K6_PID=$!

# The script prints E2E_CLICK_AT once its setup is done and the clicks are scheduled.
for _ in $(seq 1 120); do
  grep -q 'E2E_CLICK_AT' "$LOG" && break
  kill -0 "$K6_PID" 2>/dev/null || { echo "k6 exited before the clicks"; cat "$LOG"; exit 1; }
  sleep 1
done
grep -q 'E2E_CLICK_AT' "$LOG" || { echo "no clicks logged within 120 s"; cat "$LOG"; kill "$K6_PID" 2>/dev/null; exit 1; }

echo "clicks scheduled; pausing postgres for 10 s"
docker compose pause postgres
paused=1
sleep 10
docker compose unpause postgres
paused=0
echo "postgres unpaused"

status=0
wait "$K6_PID" || status=$?
cat "$LOG"
exit "$status"
