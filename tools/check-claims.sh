#!/usr/bin/env bash
# Every number in README.md, docs/report/report.md and docs/resume.md must come from results/.
#
# Two checks:
# 1. (phase 38) README's Claims table: every row links a file that exists and holds every number
#    in the claim.
# 2. (phase 42) A prose scan of README.md, docs/report/report.md and docs/resume.md. Each file fails
#    if it contains "__" (an unfilled placeholder) or the banned phrase "server crashed", or if a
#    number in its prose is found neither in the evidence files below nor in a file the same line
#    cites (a Markdown link or a `backticked` path, globs allowed). Evidence files:
#      results/headline.md, results/SUMMARY.md, results/failure/SUMMARY.md, results/coldstart.json,
#      results/billing/cost-per-1000.md, and docs/tests.md (the race-test count the resume quotes).
#    Not checked, because they are names rather than measurements: numbers inside code (fenced
#    blocks and `inline code`), link targets, dates (2026-11-25, 25 Nov 2026, 6-25 Nov 2026),
#    heading and list numbering, digits joined to letters (SHA-256, F3, p95, V4, HS256, 10x, Q1, D8),
#    and version or label numbers after a fixed word (Java 21, RFC 9457, phase 40, day 17, ...).
#
# Usage: bash tools/check-claims.sh            (all three files)
#        bash tools/check-claims.sh FILE...    (only these files; README's table check runs if
#                                               README.md is among them)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 || PYTHON=python

if [[ $# -eq 0 ]]; then
  set -- README.md docs/report/report.md docs/resume.md
fi

"$PYTHON" -I - "$@" <<'PY'
import glob
import re
import sys
from pathlib import Path

ROOT = Path.cwd()
EVIDENCE = [
    "results/headline.md",
    "results/SUMMARY.md",
    "results/failure/SUMMARY.md",
    "results/coldstart.json",
    "results/billing/cost-per-1000.md",
    "docs/tests.md",
]
NUM = re.compile(r"(?<![\d.])\d[\d,]*(?:\.\d+)?")
LINK = re.compile(r"!?\[([^\]]*)\]\(([^)\s]+)\)")
CODE_SPAN = re.compile(r"`([^`]*)`")
UNIT = re.compile(r"(?:ms|s|min|h|MB|MiB|GB|kB|Mbps|Mbit|vCPU)\b")
MONTHS = r"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*"
DATES = [
    re.compile(r"\b\d{4}-\d{2}-\d{2}(?:T[\d:.]+Z?)?\b"),
    re.compile(r"\b\d{1,2}(?:\s*-\s*\d{1,2})?\s+" + MONTHS + r"(?:\s+\d{4})?\b"),
    re.compile(r"\b" + MONTHS + r"\s+\d{4}\b"),
    re.compile(r"\b\d{1,2}:\d{2}(?::\d{2})?\b"),
]
# Words after which a number is a name or version, not a measurement.
LABEL = re.compile(
    r"\b(?:Java|RFC|PostgreSQL|Postgres|Boot|Spring Boot|Python|k6 v|v|phase|Phase|phases|day|Day|"
    r"days|chapter|Chapter|appendix|Appendix|Jackson|JUnit|Testcontainers|Flyway|Lighthouse|"
    r"Mbps profile|HTTP|status|section|Section|item|items|prediction|Prediction|predictions|"
    r"R|S|G|D|F|Q|V)\s?$"
)


def flatten(text: str) -> str:
    # "14,842.6" in prose matches 14842.6 in JSON and the reverse; "1,000" matches 1000.
    return re.sub(r"(?<=\d),(?=\d{3}(?!\d))", "", text)


def read(path: Path) -> str:
    return flatten(path.read_text(encoding="utf-8", errors="replace"))


EVIDENCE_TEXT = "\n".join(read(ROOT / p) for p in EVIDENCE if (ROOT / p).is_file())
missing_evidence = [p for p in EVIDENCE if not (ROOT / p).is_file()]


def found(tok: str, body: str) -> bool:
    if re.search(r"(?<![\d.])" + re.escape(tok) + r"(?!\d)", body):
        return True
    # "1.50" in prose matches "1.5" or "0.0150" only if written the same way; allow a trailing
    # zero difference (1.50 vs 1.5) but nothing looser.
    if "." in tok:
        short = tok.rstrip("0").rstrip(".")
        if short != tok and re.search(r"(?<![\d.])" + re.escape(short) + r"(?![\d])", body):
            return True
    return False


def cited_files(line: str, doc: Path) -> list[Path]:
    """Local files a line cites: Markdown link targets and backticked paths (globs allowed)."""
    cands = [m.group(2) for m in LINK.finditer(line)] + CODE_SPAN.findall(line)
    out: list[Path] = []
    for c in cands:
        c = c.split("#", 1)[0].strip()
        if not c or "://" in c or " " in c or "/" not in c and "." not in c:
            continue
        for base in (doc.parent, ROOT):
            hits = [Path(h) for h in glob.glob(str(base / c))]
            files = [h for h in hits if h.is_file()]
            if files:
                out.extend(files)
                break
    return out


def prose_numbers(line: str) -> list[str]:
    s = LINK.sub(lambda m: "" if m.group(0).startswith("!") else m.group(1), line)
    s = CODE_SPAN.sub(" ", s)
    s = re.sub(r"<[^>]+>", " ", s)
    for d in DATES:
        s = d.sub(" ", s)
    # Heading numbers ("## 3 System design", "### 8.2") and list markers ("1. ", "- 2) ").
    s = re.sub(r"^\s*#+\s*[\d.]+\s", " ", s)
    s = re.sub(r"^\s*(?:[-*]\s+)?\d+[.)]\s", " ", s)
    out = []
    for m in NUM.finditer(s):
        start, end = m.start(), m.end()
        tok = m.group(0).rstrip(",")
        before = s[max(0, start - 16):start]
        after = s[end:end + 1]
        prev = s[start - 1] if start else ""
        if prev.isalpha() or prev in "-_/" and start >= 2 and s[start - 2].isalpha():
            continue  # p95, F3, SHA-256, V4, HS256, a/b
        if after == "_" or after.isalpha() and not UNIT.match(s, end):
            continue  # 2x, 10x, 4th; but 25MB or 150ms is still a number
        if LABEL.search(before):
            continue
        out.append(tok.replace(",", ""))
    return out


def scan(doc: Path) -> list[str]:
    errors: list[str] = []
    raw = doc.read_text(encoding="utf-8")
    if "__" in raw:
        for n, ln in enumerate(raw.splitlines(), 1):
            if "__" in ln:
                errors.append(f'line {n}: contains "__" (an unfilled placeholder)')
    if "server crashed" in raw.lower():
        errors.append('contains the banned phrase "server crashed"')
    in_fence = False
    for n, line in enumerate(raw.splitlines(), 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        nums = prose_numbers(line)
        if not nums:
            continue
        extra = "\n".join(read(p) for p in cited_files(line, doc))
        for tok in nums:
            if not (found(tok, EVIDENCE_TEXT) or (extra and found(tok, extra))):
                errors.append(f"line {n}: {tok!r} is not in the evidence files or a file this line cites  [{line.strip()[:70]}]")
    return errors


def readme_table(readme: Path) -> list[str]:
    text = readme.read_text(encoding="utf-8")
    errors: list[str] = []
    m = re.search(r"^## Claims\s*$(.*?)(?=^## |\Z)", text, re.S | re.M)
    if not m:
        return ["README has no '## Claims' section"]
    rows = [ln for ln in m.group(1).splitlines() if ln.lstrip().startswith("|")][2:]
    if not rows:
        return ["the Claims table has no rows"]
    for n, row in enumerate(rows, start=1):
        c = [x.strip() for x in row.strip().strip("|").split("|")]
        if len(c) < 3:
            errors.append(f"claim row {n}: expected claim | file | how measured, got {len(c)} cells")
            continue
        claim, source = c[0], c[1]
        link = LINK.search(source)
        if not link:
            errors.append(f"claim row {n}: no linked file in {source!r}")
            continue
        target = link.group(2).split("#", 1)[0]
        path = readme.parent / target
        if not path.is_file():
            errors.append(f"claim row {n}: linked path is missing: {target}")
            continue
        body = read(path)
        for tok in NUM.findall(claim):
            tok = tok.rstrip(",").replace(",", "")
            if not re.search(r"(?<![\d.])" + re.escape(tok) + r"(?!\d)", body):
                errors.append(f"claim row {n}: {tok!r} does not appear in {target}  [{claim[:60]}...]")
    return errors


failed = False
if missing_evidence:
    print("check-claims FAILED: evidence files missing: " + ", ".join(missing_evidence))
    sys.exit(1)
for arg in sys.argv[1:]:
    doc = ROOT / arg
    if not doc.is_file():
        print(f"FAILED {arg}: file is missing")
        failed = True
        continue
    errors = scan(doc)
    extra = ""
    if doc.name == "README.md":
        table = readme_table(doc)
        errors += table
        rows = len(re.findall(r"^\|", re.search(r"^## Claims\s*$(.*?)(?=^## |\Z)", doc.read_text(encoding="utf-8"), re.S | re.M).group(1), re.M)) - 2 if not table else 0
        extra = f", {rows} claims in the table" if rows else ""
    if errors:
        failed = True
        print(f"FAILED {arg}:")
        for e in errors:
            print(f"  - {e}")
    else:
        print(f"OK {arg}: every number traces to results/{extra}")
sys.exit(1 if failed else 0)
PY
