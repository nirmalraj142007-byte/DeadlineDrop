#!/usr/bin/env bash
# Phase 38: every number in the README's Claims table must come from the file the row links to.
# Fails if a linked path is missing, if a number in the claim does not appear in that file, or if
# README.md contains "__" or the phrase "server crashed" (AGENTS.md bans that claim).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

README="${1:-README.md}"
PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 || PYTHON=python

"$PYTHON" -I - "$README" <<'PY'
import re
import sys
from pathlib import Path

readme = Path(sys.argv[1])
text = readme.read_text(encoding="utf-8")
errors: list[str] = []

if "__" in text:
    errors.append('README contains "__" (an unfilled placeholder)')
if "server crashed" in text.lower():
    errors.append('README contains the banned phrase "server crashed"')

# The Claims section: from its heading to the next "## " heading.
m = re.search(r"^## Claims\s*$(.*?)(?=^## |\Z)", text, re.S | re.M)
if not m:
    errors.append("README has no '## Claims' section")
    rows: list[str] = []
else:
    rows = [ln for ln in m.group(1).splitlines() if ln.lstrip().startswith("|")]

# Header and separator rows come first; every later row is a claim.
data = [r for r in rows[2:]]
if not data:
    errors.append("the Claims table has no rows")

LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")


def cells(row: str) -> list[str]:
    return [c.strip() for c in row.strip().strip("|").split("|")]


def numbers(claim: str) -> list[str]:
    # Every number in the claim, including the digits of labels such as p95, must be in the file.
    out = []
    for tok in re.findall(r"(?<![\d.])\d[\d,]*(?:\.\d+)?", claim):
        tok = tok.rstrip(",").replace(",", "")
        out.append(tok)
    return out


for n, row in enumerate(data, start=1):
    c = cells(row)
    if len(c) < 3:
        errors.append(f"claim row {n}: expected claim | file | how measured, got {len(c)} cells")
        continue
    claim, source = c[0], c[1]
    link = LINK.search(source)
    if not link:
        errors.append(f"claim row {n}: no linked file in {source!r}")
        continue
    target = link.group(1).split("#", 1)[0]
    path = (readme.parent / target)
    if not path.is_file():
        errors.append(f"claim row {n}: linked path is missing: {target}")
        continue
    body = path.read_text(encoding="utf-8", errors="replace")
    # Remove thousands separators so "14,842.6" in prose matches 14842.6 in JSON, and the
    # reverse; "1,000" matches 1000.
    flat = re.sub(r"(?<=\d),(?=\d{3}(?!\d))", "", body)
    for tok in numbers(claim):
        if not re.search(r"(?<![\d.])" + re.escape(tok) + r"(?!\d)", flat):
            errors.append(f"claim row {n}: {tok!r} does not appear in {target}  [{claim[:60]}...]")

if errors:
    print("check-claims FAILED:")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)

print(f"OK: {len(data)} claims, every linked file exists and holds every number in its claim")
PY
