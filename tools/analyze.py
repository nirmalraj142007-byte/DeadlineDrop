#!/usr/bin/env python3
"""Score one run folder against k6 ground truth and regenerate results/SUMMARY.md.

    python tools/analyze.py <run_dir>

Inputs (all inside <run_dir>):
  meta.json                  arm (a|b|c), T (ISO-8601 UTC deadline), counted, ...
  vu-log*.ndjson             one k6 line per simulated student; run.sh writes one file per VM
                             (vu-log-<cohort>.ndjson), the local runner writes vu-log.ndjson.
                             Lines are raw JSON or k6 logfmt (msg="<escaped JSON>").
  export.json                the teacher export for the run's assignment (keyed by studentEmail)
  schedule/shard-all.json    optional: lets the script count students that never logged a line

Ground truth comes ONLY from k6 clickMs (docs: DO NOT CHANGE 6):
  truly on time  clickMs <= T - 5000
  margin         T - 5000 < clickMs <= T   (reported separately, in neither denominator)
  truly late     clickMs > T

Recorded outcome comes ONLY from the export:
  arm C   the student's live row (status not EXPIRED or SUPERSEDED). VERIFIED -> its timing;
          no live row or REJECTED_HASH_MISMATCH -> MISSING; COMMITTED or UPLOADED at export time
          -> MISSING, and also counted in checks.pendingAtExport.
  arm A/B the student's baseline row (the earliest one if there are several) -> its timing.

Output: <run_dir>/confusion.json, and <run_dir>/../../SUMMARY.md rebuilt from every run under
<run_dir>/.. whose meta.json says "counted": true.

Exit codes: 0 ok; 2 unreadable input; 3 arm C with onTime_lateOrMissing > 0 or late_onTime > 0
(review G3: the real design must misjudge nobody).
"""

from __future__ import annotations

import json
import math
import re
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

MARGIN_MS = 5000
EXIT_OK = 0
EXIT_INPUT = 2
EXIT_G3 = 3

ON_TIME = "ON_TIME"
LATE = "LATE"
MISSING = "MISSING"
MARGIN = "MARGIN"

C_NOT_LIVE = {"EXPIRED", "SUPERSEDED"}
C_PENDING = {"COMMITTED", "UPLOADED"}
CONNECTION_ERROR_STATUSES = {0, 503}

LOGFMT_MSG = re.compile(r'\bmsg=("(?:[^"\\]|\\.)*")')
SERVER_TIMING_ENTRY = re.compile(r"^\s*([A-Za-z0-9_-]+)\s*(?:;.*?\bdur=([0-9.]+))?")


class InputError(Exception):
    """A run folder is missing a file or holds one that cannot be read."""


# ---------------------------------------------------------------- reading


def load_json(path: Path):
    try:
        with path.open(encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError as e:
        raise InputError(f"missing {path}") from e
    except json.JSONDecodeError as e:
        raise InputError(f"{path} is not valid JSON: {e}") from e


def deadline_ms(meta: dict) -> int:
    if "tEpochMs" in meta:
        return int(meta["tEpochMs"])
    t = meta.get("T")
    if not t:
        raise InputError("meta.json has neither T nor tEpochMs")
    try:
        dt = datetime.fromisoformat(str(t).replace("Z", "+00:00"))
    except ValueError as e:
        raise InputError(f"meta.json T is not ISO-8601: {t!r}") from e
    if dt.tzinfo is None:
        raise InputError(f"meta.json T has no time zone: {t!r}")
    return int(round(dt.timestamp() * 1000))


def parse_vu_line(raw: str):
    """Return the student record in one vu-log line, or None for other k6 output."""
    raw = raw.strip()
    if not raw:
        return None
    if raw.startswith("{"):
        obj = json.loads(raw)
    else:
        m = LOGFMT_MSG.search(raw)
        if not m:
            return None
        inner = json.loads(m.group(1))
        if not isinstance(inner, str) or not inner.lstrip().startswith("{"):
            return None
        obj = json.loads(inner)
    if isinstance(obj, dict) and "email" in obj and "clickMs" in obj:
        return obj
    return None


def read_vu_logs(run_dir: Path):
    files = sorted(run_dir.glob("vu-log*.ndjson"))
    if not files:
        raise InputError(f"no vu-log*.ndjson in {run_dir}")
    students: dict[str, dict] = {}
    duplicate_lines = 0
    unparsed = 0
    for path in files:
        with path.open(encoding="utf-8") as f:
            for n, raw in enumerate(f, start=1):
                try:
                    rec = parse_vu_line(raw)
                except json.JSONDecodeError:
                    unparsed += 1
                    continue
                if rec is None:
                    continue
                email = rec["email"]
                if email in students:
                    duplicate_lines += 1
                    continue
                rec["_source"] = f"{path.name}:{n}"
                students[email] = rec
    return students, [p.name for p in files], duplicate_lines, unparsed


def read_export(run_dir: Path) -> dict[str, list[dict]]:
    rows = load_json(run_dir / "export.json")
    if not isinstance(rows, list):
        raise InputError("export.json is not a JSON array")
    by_email: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        email = r.get("studentEmail")
        if not email:
            raise InputError(f"export row without studentEmail: {r}")
        by_email[email].append(r)
    return by_email


def read_schedule_emails(run_dir: Path):
    path = run_dir / "schedule" / "shard-all.json"
    if not path.exists():
        return None
    return {s["email"] for s in load_json(path)}


# ---------------------------------------------------------------- classification


def truth(click_ms: int, t_ms: int) -> str:
    if click_ms <= t_ms - MARGIN_MS:
        return ON_TIME
    if click_ms <= t_ms:
        return MARGIN
    return LATE


def stamp_key(row: dict):
    return (row.get("stampedAt") or "", row.get("submissionId") or "")


def recorded(arm: str, rows: list[dict]):
    """Return (outcome, chosen row or None, pending flag)."""
    if arm == "C":
        live = [r for r in rows if r.get("status") not in C_NOT_LIVE]
        if not live:
            return MISSING, None, False
        verified = sorted((r for r in live if r.get("status") == "VERIFIED"), key=stamp_key)
        if verified:
            row = verified[0]
            return timing_outcome(row), row, False
        row = sorted(live, key=stamp_key)[0]
        return MISSING, row, row.get("status") in C_PENDING
    if not rows:
        return MISSING, None, False
    row = sorted(rows, key=stamp_key)[0]
    return timing_outcome(row), row, False


def timing_outcome(row: dict) -> str:
    t = row.get("timing")
    if t in (ON_TIME, LATE):
        return t
    raise InputError(f"export row has unknown timing {t!r}: {row}")


def has_final_record(arm: str, rows: list[dict]) -> bool:
    if arm == "C":
        return any(r.get("status") == "VERIFIED" for r in rows)
    return bool(rows)


def live_count(arm: str, rows: list[dict]) -> int:
    if arm == "C":
        return sum(1 for r in rows if r.get("status") not in C_NOT_LIVE)
    return len(rows)  # arms A and B have no idempotency: every baseline row counts


def is_2xx(status) -> bool:
    return isinstance(status, int) and 200 <= status < 300


# ---------------------------------------------------------------- statistics


def percentile(values: list[float], q: float):
    """Linear interpolation between closest ranks (numpy's default method)."""
    if not values:
        return None
    s = sorted(values)
    if len(s) == 1:
        return float(s[0])
    pos = (len(s) - 1) * q
    lo = math.floor(pos)
    hi = math.ceil(pos)
    return float(s[lo] + (s[hi] - s[lo]) * (pos - lo))


def latency(values: list[float]) -> dict:
    return {
        "n": len(values),
        "p50": round_or_none(percentile(values, 0.50)),
        "p95": round_or_none(percentile(values, 0.95)),
        "p99": round_or_none(percentile(values, 0.99)),
    }


def round_or_none(v):
    return None if v is None else round(v, 1)


def parse_server_timing(header) -> dict[str, float]:
    if not header or not isinstance(header, str):
        return {}
    out = {}
    for part in header.split(","):
        m = SERVER_TIMING_ENTRY.match(part)
        if m and m.group(2) is not None:
            out[m.group(1)] = float(m.group(2))
    return out


def connection_errors(rec: dict) -> list[int]:
    """Every 503 or connection-level (status 0) response this student saw, retries included."""
    if isinstance(rec.get("failedStatuses"), list):
        seen = [s.get("status") for s in rec["failedStatuses"] if isinstance(s, dict)]
    else:  # older vu-log lines carry only the last status of each step
        seen = [rec.get("commitStatus"), rec.get("putStatus"), rec.get("finalizeStatus")]
    return [s for s in seen if s in CONNECTION_ERROR_STATUSES]


# ---------------------------------------------------------------- analysis


def analyze(run_dir: Path) -> dict:
    meta = load_json(run_dir / "meta.json")
    arm = str(meta.get("arm", "")).upper()
    if arm not in ("A", "B", "C"):
        raise InputError(f"meta.json arm must be a, b or c, got {meta.get('arm')!r}")
    t_ms = deadline_ms(meta)
    students, vu_files, duplicate_vu_lines, unparsed = read_vu_logs(run_dir)
    export = read_export(run_dir)
    scheduled = read_schedule_emails(run_dir)

    cells = {"onTime_onTime": 0, "onTime_lateOrMissing": 0, "late_late": 0, "late_onTime": 0}
    on_time_detail = {"recordedLate": 0, "recordedMissing": 0}
    margin = {ON_TIME: 0, LATE: 0, MISSING: 0}
    totals = {"trulyOnTime": 0, "margin": 0, "trulyLate": 0}
    misjudged = []
    lost, pending = [], []
    conn_total, conn_students = 0, 0
    commit_lat, final_lat = [], []
    st_values: dict[str, list[float]] = defaultdict(list)

    for email, rec in sorted(students.items()):
        rows = export.get(email, [])
        outcome, row, is_pending = recorded(arm, rows)
        g = truth(int(rec["clickMs"]), t_ms)

        if g == ON_TIME:
            totals["trulyOnTime"] += 1
            if outcome == ON_TIME:
                cells["onTime_onTime"] += 1
            else:
                cells["onTime_lateOrMissing"] += 1
                on_time_detail["recordedLate" if outcome == LATE else "recordedMissing"] += 1
        elif g == LATE:
            totals["trulyLate"] += 1
            if outcome == ON_TIME:
                cells["late_onTime"] += 1
            else:
                cells["late_late"] += 1
        else:
            totals["margin"] += 1
            margin[outcome] += 1

        if (g == ON_TIME and outcome != ON_TIME) or (g == LATE and outcome == ON_TIME):
            misjudged.append({
                "email": email,
                "truth": g,
                "recorded": outcome,
                "clickMinusTMs": int(rec["clickMs"]) - t_ms,
                "status": row.get("status") if row else None,
                "stampedAt": row.get("stampedAt") if row else None,
                "rowsInExport": len(rows),
                "k6Error": rec.get("error"),
                "commitStatus": rec.get("commitStatus"),
                "putStatus": rec.get("putStatus"),
                "finalizeStatus": rec.get("finalizeStatus"),
                "vuLog": rec.get("_source"),
            })

        if is_2xx(rec.get("finalizeStatus")) and not has_final_record(arm, rows):
            lost.append(email)
        if is_pending:
            pending.append(email)

        errs = connection_errors(rec)
        conn_total += len(errs)
        conn_students += 1 if errs else 0

        if is_2xx(rec.get("commitStatus")) and isinstance(rec.get("commitMs"), (int, float)):
            commit_lat.append(float(rec["commitMs"]))
        final_ms = rec.get("finalizeMs")
        if arm == "A" and final_ms is None:
            final_ms = rec.get("putMs")  # arm A's only request is its final step
        if is_2xx(rec.get("finalizeStatus")) and isinstance(final_ms, (int, float)):
            final_lat.append(float(final_ms))

        if arm == "C" and is_2xx(rec.get("commitStatus")):
            for phase, dur in parse_server_timing(rec.get("serverTiming")).items():
                st_values[phase].append(dur)

    duplicates = sorted(e for e, rows in export.items() if live_count(arm, rows) > 1)
    on_time_total = totals["trulyOnTime"]
    rate = cells["onTime_lateOrMissing"] / on_time_total if on_time_total else None

    result = {
        "arm": arm,
        "counted": bool(meta.get("counted", False)),
        "scheduleMode": meta.get("scheduleMode"),
        "T": meta.get("T"),
        "tEpochMs": t_ms,
        "marginMs": MARGIN_MS,
        "students": len(students),
        **totals,
        **cells,
        "onTimeLateOrMissingDetail": on_time_detail,
        "misclassificationRate": None if rate is None else round(rate, 6),
        "marginGroup": {"total": totals["margin"], **margin},
        "checks": {
            "lost": len(lost),
            "lostStudents": lost,
            "duplicates": len(duplicates),
            "duplicateStudents": duplicates,
            "pendingAtExport": len(pending),
            "pendingStudents": pending,
            "unloggedStudents": None if scheduled is None else len(scheduled - students.keys()),
            "exportRowsWithoutVuLog": sum(len(r) for e, r in export.items() if e not in students),
            "duplicateVuLines": duplicate_vu_lines,
            "unparsedVuLines": unparsed,
        },
        "connectionErrors": {"responses": conn_total, "students": conn_students},
        "latencyMs": {"commit": latency(commit_lat), "finalStep": latency(final_lat)},
        "serverTimingMedianMs": None,
        "misjudged": misjudged,
        "inputs": {"vuLogs": vu_files, "export": "export.json"},
    }
    if arm == "C":
        result["serverTimingMedianMs"] = server_timing_summary(st_values)
    return result


def server_timing_summary(values: dict[str, list[float]]):
    if not values:
        return {"n": 0}
    out = {"n": max(len(v) for v in values.values())}
    for phase in ("app", "db", "sign"):
        out[phase] = round_or_none(percentile(values.get(phase, []), 0.5))
    # app is the whole request from arrival to response, so db and sign are inside it.
    # "other" (app minus db minus sign, per request) is the remaining in-app time.
    others = [a - d - s for a, d, s in zip(values.get("app", []), values.get("db", []), values.get("sign", []))]
    out["other"] = round_or_none(percentile(others, 0.5))
    parts = {k: out[k] for k in ("db", "sign", "other") if out.get(k) is not None}
    out["largestPhase"] = max(parts, key=parts.get) if parts else None
    return out


# ---------------------------------------------------------------- SUMMARY.md


def write_summary(runs_dir: Path, summary_path: Path) -> None:
    lines = [
        "# Results summary",
        "",
        "Generated by `tools/analyze.py` from every run in `results/runs/` whose `meta.json` says",
        '`"counted": true`. Never edit by hand; dry runs (`"counted": false`) are not listed.',
        "",
    ]
    counted = []
    for meta_path in sorted(runs_dir.glob("*/meta.json")):
        try:
            meta = load_json(meta_path)
        except InputError:
            continue
        if meta.get("counted") is True:
            counted.append(meta_path.parent)
    if not counted:
        lines.append("No counted runs yet.")
    else:
        lines += [
            "| Run | Arm | Students | Truly on time | onTime_lateOrMissing | late_onTime | Misclassification rate | Lost | Duplicates |",
            "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
        for d in counted:
            cpath = d / "confusion.json"
            if not cpath.exists():
                lines.append(f"| `{d.name}` | not analysed | | | | | | | |")
                continue
            c = load_json(cpath)
            rate = c.get("misclassificationRate")
            lines.append(
                f"| `{d.name}` | {c['arm']} | {c['students']} | {c['trulyOnTime']} | "
                f"{c['onTime_lateOrMissing']} | {c['late_onTime']} | "
                f"{'n/a' if rate is None else f'{rate:.4f}'} | {c['checks']['lost']} | {c['checks']['duplicates']} |"
            )
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- main


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("Usage: analyze.py <run_dir>", file=sys.stderr)
        return EXIT_INPUT
    run_dir = Path(argv[1]).resolve()
    try:
        result = analyze(run_dir)
    except InputError as e:
        print(f"analyze.py: {e}", file=sys.stderr)
        return EXIT_INPUT

    with (run_dir / "confusion.json").open("w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
        f.write("\n")
    write_summary(run_dir.parent, run_dir.parent.parent / "SUMMARY.md")

    rate = result["misclassificationRate"]
    print(f"arm {result['arm']}: {result['students']} students "
          f"(on time {result['trulyOnTime']}, margin {result['margin']}, late {result['trulyLate']})")
    print(f"  onTime_onTime {result['onTime_onTime']}  onTime_lateOrMissing {result['onTime_lateOrMissing']}  "
          f"late_late {result['late_late']}  late_onTime {result['late_onTime']}  "
          f"rate {'n/a' if rate is None else f'{rate:.4f}'}")
    ck = result["checks"]
    print(f"  lost {ck['lost']}  duplicates {ck['duplicates']}  pending {ck['pendingAtExport']}  "
          f"connectionErrors {result['connectionErrors']['responses']}")
    if result["serverTimingMedianMs"]:
        print(f"  Server-Timing medians (ms): {result['serverTimingMedianMs']}")

    if result["arm"] == "C" and (result["onTime_lateOrMissing"] > 0 or result["late_onTime"] > 0):
        print("G3 FAILED: arm C misjudged these students:", file=sys.stderr)
        for m in result["misjudged"]:
            print(f"  {m['email']}: truth {m['truth']}, recorded {m['recorded']}, "
                  f"click-T {m['clickMinusTMs']} ms, status {m['status']}, k6 error {m['k6Error']}",
                  file=sys.stderr)
        return EXIT_G3
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv))
