#!/usr/bin/env python3
"""Write results/failure/SUMMARY.md: one row per failure-test run (phase 34).

Usage: python tools/failure_summary.py [--results results]

Reads results/runs/*/meta.json, confusion.json and fault-<name>.json for every run whose fault is
f1, f2, f3, f3-noreceipt or coldrush, counted or marked invalid (invalid runs are listed with their
reason, never dropped). Each row links its folder. The table is never edited by hand; the prose in
results/failure/NOTES.md (what each row means) is appended under it verbatim.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze  # noqa: E402  (same folder; reads the vu-logs the same way)

FAULTS = ("f1", "f2", "f3", "f3-noreceipt", "coldrush")
FAULT_TITLE = {
    "f1": "F1 instance crash (halt at T-60 s)",
    "f2": "F2 browser dies after upload (10% skip finalize)",
    "f3": "F3 Cloud SQL restart at T-90 s",
    "f3-noreceipt": "F3 without the signed receipt",
    "coldrush": "Cold rush (arm C min-instances 0 after 15 min idle)",
}


def load(path: Path):
    if not path.exists():
        return None
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def num(v) -> str:
    return "n/a" if v is None else str(v)


def receipts_issued(run: Path) -> int:
    students, _, _, _ = analyze.read_vu_logs(run)
    return sum(1 for r in students.values() if r.get("receiptIssued"))


def seconds(ms) -> str:
    return "n/a" if ms is None else f"{ms / 1000:+.1f} s"


def fault_detail(fault: str, run: Path, conf: dict | None) -> str:
    rec = load(run / f"fault-{fault}.json") or {}
    if fault == "f1":
        if not rec.get("haltSentAt"):
            return "halt not sent"
        return (f"halt sent at T{seconds(rec.get('haltSentMinusTMs'))}, HTTP {rec.get('haltHttpCode')}, "
                f"instance `{rec.get('instance')}`; chaos off after: {rec.get('chaosStatusAfter')}")
    if fault in ("f3", "f3-noreceipt"):
        if not rec.get("restartRequestedAt"):
            return "restart not requested"
        down = rec.get("unhealthyForMs")
        return (f"restart at T{seconds(rec.get('restartRequestedMinusTMs'))}; health failed "
                f"{rec.get('failedHealthChecks')} of {rec.get('healthChecks')} checks"
                + (f", unhealthy {down / 1000:.1f} s" if down is not None else "")
                + f", healthy again at T{seconds(rec.get('firstHealthyAfterRestartMinusTMs'))}"
                + f"; receipts issued: {receipts_issued(run)}")
    if fault == "f2":
        f2 = (conf or {}).get("f2")
        if not f2:
            return "no f2 block"
        s = f2["secondsUploadEndToUploaded"]
        statuses = ", ".join(f"{k} {v}" for k, v in f2["finalStatus"].items())
        return (f"{f2['skippedFinalize']} skipped finalize ({f2['uploadSucceeded']} uploaded): {statuses}; "
                f"upload end to UPLOADED p50 {num(s['p50'])} s, max {num(s['max'])} s")
    if fault == "coldrush":
        meta = load(run / "meta.json") or {}
        return f"min-instances {meta.get('minInstances')}; see results/coldstart.json cold_rush"
    return ""


def row(run: Path, root: Path) -> list[str] | None:
    meta = load(run / "meta.json")
    if not meta or meta.get("fault") not in FAULTS:
        return None
    conf = load(run / "confusion.json")
    link = f"[`{run.name}`](../runs/{run.name}/)"
    arm = str(meta.get("arm", "")).upper()
    fault = meta["fault"]
    if meta.get("valid") is False or conf is None:
        reason = meta.get("invalidReason", "no confusion.json")
        return [link, arm, fault, "invalid", "", "", "", "", "", "", f"invalid: {reason}"]
    c = conf["checks"]
    misj = f"{conf['onTime_lateOrMissing']} / {conf['trulyOnTime']}"
    rate = conf.get("misclassificationRate")
    misj += f" ({rate * 100:.2f}%)" if rate is not None else ""
    return [
        link, arm, fault, "yes" if meta.get("counted") else "dry run",
        misj, str(conf["late_onTime"]), str(c["lost"]), str(c["duplicates"]),
        f"{conf['connectionErrors']['responses']} / {conf['connectionErrors']['students']}",
        str(conf["latencyMs"]["commit"]["p95"]),
        fault_detail(fault, run, conf),
    ]


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--results", default="results")
    args = p.parse_args(argv)
    results = Path(args.results)
    rows = []
    for run in sorted((results / "runs").iterdir()):
        if run.is_dir():
            r = row(run, results.resolve().parent)
            if r:
                rows.append(r)
    if not rows:
        print("failure_summary.py: no failure-test runs found", file=sys.stderr)
        return 1
    out = results / "failure" / "SUMMARY.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    head = ["Run", "Arm", "Fault", "Counted", "On-time misjudged", "Late to on-time", "Lost",
            "Duplicates", "503/conn. errors (responses / students)", "p95 commit ms", "What happened"]
    lines = [
        "# Failure tests (day 17, phase 34)",
        "",
        "Generated by `tools/failure_summary.py` from each run folder's `meta.json`, `confusion.json` and",
        "`fault-<name>.json`; never edited by hand. Faults: "
        + "; ".join(f"`{k}` {v}" for k, v in FAULT_TITLE.items()) + ".",
        "\"On-time misjudged\" = truly on-time students (clicked at least 5 s before T) recorded late or missing.",
        "Fault times are relative to the deadline T. The notes under the table come from `NOTES.md` in this folder.",
        "",
        "| " + " | ".join(head) + " |",
        "|" + "|".join("---" for _ in head) + "|",
    ]
    lines += ["| " + " | ".join(r) + " |" for r in rows]
    notes = results / "failure" / "NOTES.md"
    if notes.exists():
        lines += ["", notes.read_text(encoding="utf-8").rstrip()]
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out} ({len(rows)} runs)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
