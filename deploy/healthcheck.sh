#!/usr/bin/env bash
# Container healthcheck for the demo stack (the JRE image has bash but no curl).
# Usage: healthcheck.sh <port> [path]
#   with a path: GET it and require "UP" in the answer; without: just require the port to accept.
set -euo pipefail
port="$1"
path="${2:-}"
exec 3<>"/dev/tcp/127.0.0.1/${port}"
if [ -n "$path" ]; then
  printf 'GET %s HTTP/1.0\r\nHost: localhost\r\n\r\n' "$path" >&3
  grep -q '"status":"UP"' <&3
fi
