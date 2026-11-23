#!/usr/bin/env bash
# make e2e: build and start the stack, seed 20 students, run the happy and failure paths, tear down.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
[ -f .env ] || { echo "ERROR: .env is missing; copy .env.example and fill in the secrets" >&2; exit 1; }
SEED_PASSWORD="$(grep -E '^SEED_PASSWORD=' .env | cut -d= -f2- | tr -d '\r\n')"
: "${SEED_PASSWORD:?SEED_PASSWORD must be set in .env}"
export SEED_PASSWORD PASSWORD="$SEED_PASSWORD"
export E2E_LOG_DIR="${E2E_LOG_DIR:-target/e2e}"
mkdir -p "$E2E_LOG_DIR"

cleanup() { docker compose down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker compose up -d --build

docker compose run --rm -e SPRING_PROFILES_ACTIVE=local,seed -e SEED_STUDENTS=20 -e SEED_PASSWORD -e DB_NAME=dd_c app

for _ in $(seq 1 60); do
  curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1 && break
  sleep 2
done
curl -sf http://localhost:8080/actuator/health >/dev/null || { echo "app never became healthy"; docker compose logs app | tail -50; exit 1; }

k6 run -e PASSWORD="$PASSWORD" tools/e2e/happy.js 2>&1 | tee "$E2E_LOG_DIR/happy.log"
bash tools/e2e/failure.sh
echo "e2e: happy and failure paths passed"
