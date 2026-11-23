#!/usr/bin/env bash
# Drops and recreates dd_b and dd_c, re-runs Flyway (on the seed run), seeds 200 students, the
# teacher and an admin into both, creates DEMO101 and "Lab 4" in both, writes tools/demo/.lab4.
# Demo tooling: it destroys the demo databases on purpose. Needs `make demo` running.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

echo "== stopping the app containers (the databases cannot be dropped while they hold connections)"
dc stop nginx app-c1 app-c2 app-armb scheduler >/dev/null

echo "== dropping and recreating dd_b and dd_c"
for db in dd_b dd_c; do
  psql_db postgres -c "DROP DATABASE IF EXISTS ${db} WITH (FORCE)" >/dev/null
  psql_db postgres -c "CREATE DATABASE ${db}" >/dev/null
done

echo "== seeding 200 students, teacher, admin, DEMO101 (Flyway runs first)"
for db in dd_b dd_c; do
  dc run --rm -e SPRING_PROFILES_ACTIVE=local,seed -e SEED_STUDENTS=200 -e SEED_PASSWORD \
    -e DB_NAME="$db" app 2>&1 | grep -E "Seed completed|ERROR|Exception" || true
  count="$(psql_db "$db" -c "SELECT count(*) FROM users WHERE role='STUDENT'")"
  [ "$count" = "200" ] || { echo "ERROR: $db has $count students, expected 200" >&2; exit 1; }
done

echo "== starting the apps"
dc up -d app-c1 app-c2 nginx app-armb scheduler >/dev/null
for url in http://localhost:8080 http://localhost:8081; do
  for _ in $(seq 1 90); do
    curl -sf "$url/actuator/health" >/dev/null 2>&1 && break
    sleep 2
  done
  curl -sf "$url/actuator/health" >/dev/null || { echo "ERROR: $url never became healthy" >&2; exit 1; }
done

echo "== creating Lab 4 (grace 15 min, 25 MB, PDF) in both arms"
create_lab4() {
  local base="$1" token course_id deadline
  token="$(teacher_token "$base")"
  : "${token:?teacher login failed on $base}"
  course_id="$(curl -s -f -H "Authorization: Bearer ${token}" "$base/api/courses" | "$PYTHON" -c "
import json, sys
print(next((c['id'] for c in json.load(sys.stdin) if c.get('code') == 'DEMO101'), ''))" | tr -d '\r\n')"
  : "${course_id:?DEMO101 not found on $base}"
  deadline="$("$PYTHON" -c "
import datetime
print((datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))" | tr -d '\r\n')"
  curl -s -f -X POST "$base/api/courses/${course_id}/assignments" \
    -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" \
    -d "{\"title\":\"Lab 4\",\"deadlineAt\":\"${deadline}\",\"graceSeconds\":900,\"maxBytes\":26214400,\"allowedTypes\":[\"application/pdf\"]}" \
    | grep -o '"id":"[^"]*"' | head -n1 | cut -d'"' -f4
}
LAB4_C="$(create_lab4 http://localhost:8080)"
LAB4_B="$(create_lab4 http://localhost:8081)"
: "${LAB4_C:?Lab 4 creation failed on :8080}"
: "${LAB4_B:?Lab 4 creation failed on :8081}"
printf 'C=%s\nB=%s\n' "$LAB4_C" "$LAB4_B" > "$LAB4_FILE"
rm -rf "$LAST_RUSH_DIR"

echo "Lab 4 on :8080 (arm C, dd_c): ${LAB4_C}"
echo "Lab 4 on :8081 (arm B, dd_b): ${LAB4_B}"
echo "ids written to tools/demo/.lab4"
