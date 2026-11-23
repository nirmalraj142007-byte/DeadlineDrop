#!/usr/bin/env bash
# Everything the demo needs before an audience arrives. Exits 1 if any check fails.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

fail=0
bad() { echo "FAIL: $*"; fail=1; }
ok()  { echo "ok:   $*"; }

# 1. every demo container healthy
for svc in postgres app-c1 app-c2 nginx app-armb storage; do
  cid="$(dc ps -q "$svc" 2>/dev/null)"
  if [ -z "$cid" ]; then bad "$svc is not running (make demo)"; continue; fi
  state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid")"
  if [ "$state" = "healthy" ]; then ok "$svc healthy"; else bad "$svc is $state"; fi
done
cid="$(dc ps -q scheduler 2>/dev/null)"
if [ -n "$cid" ] && [ "$(docker inspect -f '{{.State.Status}}' "$cid")" = "running" ]; then
  ok "scheduler running"
else
  bad "scheduler is not running"
fi

# 2. ports answer (the storage server has no health route; any HTTP answer proves it listens)
for port in 8080 8081; do
  if curl -sf --max-time 5 "http://localhost:${port}/actuator/health" >/dev/null; then ok ":${port} answers"; else bad ":${port} does not answer"; fi
done
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:9000/ || true)"
if [ "$code" != "000" ] && [ -n "$code" ]; then ok ":9000 answers (HTTP ${code})"; else bad ":9000 does not answer"; fi

# 3. Lab 4 in both databases
for pair in "C:dd_c" "B:dd_b"; do
  arm="${pair%%:*}"; db="${pair##*:}"
  id="$(lab4_id "$arm" 2>/dev/null || true)"
  n=""
  if [ -n "$id" ]; then n="$(psql_db "$db" -c "SELECT count(*) FROM assignments WHERE id = '${id}' AND title = 'Lab 4'" 2>/dev/null | tr -d '\r\n' || true)"; fi
  if [ "$n" = "1" ]; then ok "Lab 4 exists in ${db}"; else bad "Lab 4 missing in ${db} (make demo-reset)"; fi
done

# 4. the 10 MB PDF used in the browser windows
PDF="$DEMO_DIR/lab4.pdf"
if [ ! -f "$PDF" ] || [ "$(wc -c < "$PDF" | tr -d ' ')" -ne 10485760 ]; then
  "$PYTHON" -I "$(command -v cygpath >/dev/null 2>&1 && cygpath -m "$DEMO_DIR" || echo "$DEMO_DIR")/make_pdf.py" "$(command -v cygpath >/dev/null 2>&1 && cygpath -m "$PDF" || echo "$PDF")" \
    || bad "could not create tools/demo/lab4.pdf"
fi
if [ -f "$PDF" ] && [ "$(wc -c < "$PDF" | tr -d ' ')" -eq 10485760 ]; then ok "tools/demo/lab4.pdf is 10 MB"; else bad "tools/demo/lab4.pdf is missing or not 10 MB"; fi

# 5. k6
if command -v k6 >/dev/null 2>&1; then ok "k6 installed ($(k6 version | head -n1))"; else bad "k6 is not installed"; fi

if [ "$fail" -ne 0 ]; then echo; echo "Preflight FAILED"; exit 1; fi
echo
echo "In Chrome: DevTools > Network > Hostel 1 Mbps (1000 kbit/s, 80 ms) in BOTH windows"
