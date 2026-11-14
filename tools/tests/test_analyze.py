"""Tests for tools/analyze.py on small hand-built run folders."""

import json
import subprocess
import sys
from pathlib import Path

import pytest

ANALYZE = Path(__file__).resolve().parents[1] / "analyze.py"
T_ISO = "2026-11-20T10:00:00Z"
T_MS = 1795168800000  # T_ISO in epoch milliseconds


def vu(email, click_offset_ms, cohort="fast", **extra):
    line = {
        "email": email, "cohort": cohort, "clickMs": T_MS + click_offset_ms,
        "commitStatus": 201, "commitMs": 40, "putStatus": 200, "putMs": 900,
        "finalizeStatus": 200, "finalizeMs": 30, "error": None,
        "serverTiming": "app;dur=20, db;dur=12, sign;dur=5",
    }
    line.update(extra)
    return line


def row(email, timing, status="VERIFIED", stamped="2026-11-20T09:59:00Z", sid=None):
    return {"studentEmail": email, "submissionId": sid or f"id-{email}-{status}",
            "stampedAt": stamped, "status": status, "timing": timing}


def make_run(tmp_path, arm, vu_lines, export_rows, name=None, counted=False, logfmt=False):
    run = tmp_path / "runs" / (name or f"run_{arm.upper()}")
    run.mkdir(parents=True)
    (run / "meta.json").write_text(json.dumps(
        {"arm": arm, "T": T_ISO, "counted": counted, "scheduleMode": "placeholder"}), encoding="utf-8")
    # one file per cohort, as run.sh writes them
    by_cohort = {}
    for line in vu_lines:
        by_cohort.setdefault(line["cohort"], []).append(line)
    for cohort, lines in by_cohort.items():
        with (run / f"vu-log-{cohort}.ndjson").open("w", encoding="utf-8") as f:
            for line in lines:
                payload = json.dumps(line)
                if logfmt:
                    f.write(f'time="2026-11-20T15:29:00+05:30" level=info msg={json.dumps(payload)} source=console\n')
                else:
                    f.write(payload + "\n")
    (run / "export.json").write_text(json.dumps(export_rows), encoding="utf-8")
    return run


def run_analyze(run):
    proc = subprocess.run([sys.executable, str(ANALYZE), str(run)], capture_output=True, text=True)
    confusion = json.loads((run / "confusion.json").read_text(encoding="utf-8"))
    return proc.returncode, confusion, proc


def test_perfect_arm_c_run_exits_zero(tmp_path):
    lines = [vu("s1@dd.local", -60000, "slow"), vu("s2@dd.local", -20000, "medium"),
             vu("s3@dd.local", 30000, "fast")]
    rows = [row("s1@dd.local", "ON_TIME"), row("s2@dd.local", "ON_TIME"), row("s3@dd.local", "LATE"),
            # a superseded earlier attempt is ignored
            row("s1@dd.local", "ON_TIME", status="SUPERSEDED", stamped="2026-11-20T09:50:00Z")]
    run = make_run(tmp_path, "c", lines, rows, logfmt=True)
    code, c, proc = run_analyze(run)
    assert code == 0, proc.stderr
    assert (c["onTime_onTime"], c["onTime_lateOrMissing"], c["late_late"], c["late_onTime"]) == (2, 0, 1, 0)
    assert c["misclassificationRate"] == 0.0
    assert c["checks"]["lost"] == 0 and c["checks"]["duplicates"] == 0
    assert sorted(c["inputs"]["vuLogs"]) == ["vu-log-fast.ndjson", "vu-log-medium.ndjson", "vu-log-slow.ndjson"]
    assert c["serverTimingMedianMs"]["db"] == 12.0
    assert c["serverTimingMedianMs"]["largestPhase"] == "db"


def test_arm_c_with_one_late_recorded_on_time_exits_three(tmp_path):
    lines = [vu("s1@dd.local", -60000), vu("s2@dd.local", 45000)]
    rows = [row("s1@dd.local", "ON_TIME"), row("s2@dd.local", "ON_TIME")]
    run = make_run(tmp_path, "c", lines, rows)
    code, c, proc = run_analyze(run)
    assert code == 3
    assert c["late_onTime"] == 1
    assert [m["email"] for m in c["misjudged"]] == ["s2@dd.local"]
    assert "s2@dd.local" in proc.stderr


def test_arm_c_expired_row_counts_as_missing_and_exits_three(tmp_path):
    run = make_run(tmp_path, "c", [vu("s1@dd.local", -60000)], [row("s1@dd.local", "ON_TIME", status="EXPIRED")])
    code, c, _ = run_analyze(run)
    assert code == 3
    assert c["onTimeLateOrMissingDetail"] == {"recordedLate": 0, "recordedMissing": 1}
    assert c["checks"]["lost"] == 1  # k6 finalize got 200, but no VERIFIED row


def test_arm_b_misclassification_rate(tmp_path):
    lines = [vu(f"s{i}@dd.local", -30000) for i in range(4)] + [vu("late@dd.local", 10000)]
    rows = [row("s0@dd.local", "ON_TIME", status="SUBMITTED"),
            row("s1@dd.local", "LATE", status="SUBMITTED"),  # slow upload: stamped at finalize, after T
            # s2: no row at all -> MISSING
            row("s3@dd.local", "ON_TIME", status="SUBMITTED"),
            row("late@dd.local", "LATE", status="SUBMITTED")]
    run = make_run(tmp_path, "b", lines, rows)
    code, c, _ = run_analyze(run)
    assert code == 0  # exit 3 applies to arm C only
    assert c["trulyOnTime"] == 4
    assert c["onTime_lateOrMissing"] == 2
    assert c["misclassificationRate"] == pytest.approx(0.5)
    assert c["checks"]["lost"] == 1  # s2 got finalize 200 but has no row
    assert c["serverTimingMedianMs"] is None


def test_margin_student_is_in_neither_denominator(tmp_path):
    lines = [vu("on@dd.local", -10000), vu("margin@dd.local", -2000), vu("late@dd.local", 2000)]
    rows = [row("on@dd.local", "ON_TIME"), row("margin@dd.local", "LATE"), row("late@dd.local", "LATE")]
    run = make_run(tmp_path, "c", lines, rows)
    code, c, _ = run_analyze(run)
    assert code == 0
    assert (c["trulyOnTime"], c["margin"], c["trulyLate"]) == (1, 1, 1)
    assert c["marginGroup"] == {"total": 1, "ON_TIME": 0, "LATE": 1, "MISSING": 0}
    assert c["misclassificationRate"] == 0.0


def test_margin_boundaries_are_inclusive(tmp_path):
    lines = [vu("a@dd.local", -5000), vu("b@dd.local", -4999), vu("c@dd.local", 0), vu("d@dd.local", 1)]
    rows = [row(e, "ON_TIME") for e in ("a@dd.local", "b@dd.local", "c@dd.local")] + [row("d@dd.local", "LATE")]
    code, c, _ = run_analyze(make_run(tmp_path, "c", lines, rows))
    assert code == 0
    assert (c["trulyOnTime"], c["margin"], c["trulyLate"]) == (1, 2, 1)


def test_arm_a_duplicates_and_connection_errors(tmp_path):
    lines = [vu("s1@dd.local", -30000, failedStatuses=[{"step": "naive", "status": 503},
                                                      {"step": "naive", "status": 0}]),
             vu("s2@dd.local", -30000)]
    rows = [row("s1@dd.local", "ON_TIME", status="SUBMITTED", sid="x1"),
            row("s1@dd.local", "ON_TIME", status="SUBMITTED", sid="x2", stamped="2026-11-20T09:59:30Z"),
            row("s2@dd.local", "ON_TIME", status="SUBMITTED")]
    code, c, _ = run_analyze(make_run(tmp_path, "a", lines, rows))
    assert code == 0
    assert c["checks"]["duplicates"] == 1 and c["checks"]["duplicateStudents"] == ["s1@dd.local"]
    assert c["connectionErrors"] == {"responses": 2, "students": 1}


def test_latency_percentiles(tmp_path):
    lines = [vu(f"s{i}@dd.local", -30000, commitMs=float(i * 10)) for i in range(1, 101)]
    rows = [row(f"s{i}@dd.local", "ON_TIME") for i in range(1, 101)]
    code, c, _ = run_analyze(make_run(tmp_path, "c", lines, rows))
    assert code == 0
    assert c["latencyMs"]["commit"] == {"n": 100, "p50": 505.0, "p95": 950.5, "p99": 990.1}


def test_summary_lists_only_counted_runs(tmp_path):
    dry = make_run(tmp_path, "c", [vu("s1@dd.local", -60000)], [row("s1@dd.local", "ON_TIME")], name="dry")
    run_analyze(dry)
    summary = (tmp_path / "SUMMARY.md").read_text(encoding="utf-8")
    assert "No counted runs yet." in summary

    counted = make_run(tmp_path, "c", [vu("s1@dd.local", -60000)], [row("s1@dd.local", "ON_TIME")],
                       name="counted", counted=True)
    run_analyze(counted)
    summary = (tmp_path / "SUMMARY.md").read_text(encoding="utf-8")
    assert "`counted`" in summary and "`dry`" not in summary
    assert "No counted runs yet." not in summary
