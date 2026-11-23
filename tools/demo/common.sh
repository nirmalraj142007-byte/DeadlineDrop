#!/usr/bin/env bash
# Shared by the demo scripts. Source it; it cds to the repo root and loads .env values.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
DEMO_DIR="$ROOT/tools/demo"
LAB4_FILE="$DEMO_DIR/.lab4"
LAST_RUSH_DIR="$DEMO_DIR/.last-rush"

[ -f .env ] || { echo "ERROR: .env is missing; copy .env.example and fill in the secrets" >&2; exit 1; }
env_get() { grep -E "^$1=" .env | head -n1 | cut -d= -f2- | tr -d '\r\n'; }
DB_USER="$(env_get DB_USER)"
SEED_PASSWORD="$(env_get SEED_PASSWORD)"
INTERNAL_TOKEN="$(env_get INTERNAL_TOKEN)"
: "${DB_USER:?DB_USER must be set in .env}"
: "${SEED_PASSWORD:?SEED_PASSWORD must be set in .env}"

dc() { docker compose -f docker-compose.yml -f docker-compose.demo.yml "$@"; }
# psql through the Postgres container (demo tooling only; the product never does this).
psql_db() { local db="$1"; shift; dc exec -T postgres psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$db" -At "$@"; }

PYTHON="${PYTHON:-$(command -v python 2>/dev/null || command -v python3 2>/dev/null || echo python)}"

# lab4_id c|b  -> prints the Lab 4 assignment id recorded by demo-reset
lab4_id() {
  [ -f "$LAB4_FILE" ] || { echo "ERROR: $LAB4_FILE missing; run make demo-reset" >&2; return 1; }
  grep -E "^$1=" "$LAB4_FILE" | cut -d= -f2- | tr -d '\r\n'
}

# teacher_token <base-url>
teacher_token() {
  curl -s -f -X POST "$1/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"email\":\"teacher@dd.local\",\"password\":\"${SEED_PASSWORD}\"}" \
    | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4
}
