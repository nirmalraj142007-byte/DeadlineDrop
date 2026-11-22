#!/usr/bin/env python3
"""Aggregate every counted run into results/SUMMARY.md and results/headline.md.

Usage: python tools/summary.py [--results results]

Reads results/runs/*/meta.json and confusion.json. A run is used when meta.json says
"counted": true, it is not marked "valid": false, and analyze.py wrote its confusion.json.
Sweep runs (fault "none") make the arm x size table; configuration variants (fault scale<N>,
phase 33 scaling experiment) are listed apart and only used for the "A at 10 instances" line.

SUMMARY.md = the arm x size table, then the per-run table that tools/analyze.py writes (analyze.py
rebuilds only that per-run part after each run; `make charts` rebuilds the whole file).
headline.md = the numbers for the repeatable sentence; every line ends with its source path(s).
Neither file is edited by hand.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze  # noqa: E402  (same folder; reuses the per-run SUMMARY table)

ARMS = ("A", "B", "C")
HEADLINE_SIZE = 1000
FALLBACK_RATE = 0.05          # PREDICTIONS.md "Fallback rule (fixed now)"
MODEL_SPEED = "1x"
COMMIT_TARGET_MS = 500


@dataclass
class Run:
    folder: Path
    meta: dict
    conf: dict

    @property
    def arm(self) -> str:
        return self.conf["arm"]

    @property
    def size(self) -> int:
        return int(self.meta["size"])

    @property
    def fault(self) -> str:
        return self.meta.get("fault") or "none"

    @property
    def rate(self) -> float:
        return float(self.conf["misclassificationRate"])

    @property
    def misjudged(self) -> int:
        return int(self.conf["onTime_lateOrMissing"])

    @property
    def error_cells(self) -> int:
        return int(self.conf["onTime_lateOrMissing"]) + int(self.conf["late_onTime"])

    def commit(self, q: str):
        return (self.conf.get("latencyMs") or {}).get("commit", {}).get(q)


@dataclass
class Cell:
    arm: str
    size: int
    runs: list[Run] = field(default_factory=list)

    def rates(self) -> list[float]:
        return [r.rate for r in self.runs]

    def median_rate(self) -> float:
        return statistics.median(self.rates())

    def total(self, key) -> int:
        return sum(key(r) for r in self.runs)

    def median_of(self, fn):
        vals = [v for v in (fn(r) for r in self.runs) if v is not None]
        return statistics.median(vals) if vals else None


def load_runs(runs_dir: Path) -> list[Run]:
    runs = []
    for meta_path in sorted(runs_dir.glob("*/meta.json")):
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        if meta.get("counted") is not True or meta.get("valid") is False:
            continue
        cpath = meta_path.parent / "confusion.json"
        if not cpath.exists():
            continue
        conf = json.loads(cpath.read_text(encoding="utf-8"))
        if conf.get("misclassificationRate") is None:
            raise SystemExit(f"summary.py: {cpath} has no misclassificationRate")
        runs.append(Run(meta_path.parent, meta, conf))
    return runs


def cells_of(runs: list[Run]) -> dict[tuple[str, int], Cell]:
    cells: dict[tuple[str, int], Cell] = {}
    for r in runs:
        if r.fault != "none":
            continue
        cells.setdefault((r.arm, r.size), Cell(r.arm, r.size)).runs.append(r)
    return dict(sorted(cells.items(), key=lambda kv: (kv[0][1], kv[0][0])))


def pct(x: float | None, digits: int = 2) -> str:
    return "n/a" if x is None else f"{x * 100:.{digits}f}%"


def ms(x) -> str:
    return "n/a" if x is None else f"{x:.0f} ms"


def rel(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


# ---------------------------------------------------------------- SUMMARY.md


def summary_table(cells: dict[tuple[str, int], Cell], variants: list[Run]) -> list[str]:
    lines = [
        "## Arm x cohort size (counted sweep runs)",
        "",
        "Misclassification = truly on-time students recorded late or missing, over truly on-time",
        "students (k6 click time, 5 s margin). Median over repeats; min-max in brackets. Counts are",
        "sums over the repeats. Connection errors = 503 or connection-level responses seen by k6,",
        "retries included. Commit p95 = median over repeats of each run's commit p95; arm A's commit",
        "request carries the file (multipart), so for A it is the whole upload, not a tiny commit.",
        "",
        "| Arm | Students | Runs | Misclassification median [min-max] | Misjudged (sum) | late_onTime | Lost | Duplicates | Connection errors | Commit p95 |",
        "| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for (arm, size), c in cells.items():
        rates = c.rates()
        lines.append(
            f"| {arm} | {size} | {len(c.runs)} | {pct(c.median_rate())} [{pct(min(rates))}-{pct(max(rates))}] | "
            f"{c.total(lambda r: r.misjudged)} | {c.total(lambda r: int(r.conf['late_onTime']))} | "
            f"{c.total(lambda r: int(r.conf['checks']['lost']))} | {c.total(lambda r: int(r.conf['checks']['duplicates']))} | "
            f"{c.total(lambda r: int(r.conf['connectionErrors']['responses']))} | {ms(c.median_of(lambda r: r.commit('p95')))} |"
        )
    if variants:
        lines += [
            "",
            "## Configuration variants (not in the table above)",
            "",
            "| Run | Arm | Students | Max instances x pool | Misclassification | Misjudged | Connection errors | Commit p95 |",
            "| --- | --- | ---: | --- | ---: | ---: | ---: | ---: |",
        ]
        for r in variants:
            lines.append(
                f"| `{r.folder.name}` | {r.arm} | {r.size} | {r.meta.get('maxInstances')} x {r.meta.get('pool')} | "
                f"{pct(r.rate)} | {r.misjudged} | {r.conf['connectionErrors']['responses']} | {ms(r.commit('p95'))} |"
            )
    return lines


def write_summary(results: Path, cells, variants) -> Path:
    runs_dir = results / "runs"
    path = results / "SUMMARY.md"
    analyze.write_summary(runs_dir, path)   # per-run table (and invalid runs)
    per_run = path.read_text(encoding="utf-8").splitlines()
    body = [l for l in per_run[1:] if not l.startswith(("Generated by", '`"counted": true`'))]
    while body and body[0] == "":
        body.pop(0)
    lines = [
        "# Results summary",
        "",
        "Generated by `tools/summary.py` (`make charts`) from every run in `results/runs/` whose",
        '`meta.json` says `"counted": true` and is not marked invalid. Never edit by hand.',
        "",
        *summary_table(cells, variants),
        "",
        "## Every counted run",
        "",
        *body,
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


# ---------------------------------------------------------------- headline.md


def headline(results: Path, root: Path, runs: list[Run], cells, variants) -> list[str]:
    model_path = results / "model" / "model-output.json"
    model = json.loads(model_path.read_text(encoding="utf-8"))["results"][MODEL_SPEED]["arms"]
    src_model = rel(model_path, root)
    out: list[str] = []

    def runs_glob(arm: str, size: int | None = None) -> str:
        return f"results/runs/*_{arm}_{size}_r*/confusion.json" if size else f"results/runs/*_{arm}_*/confusion.json"

    b = cells.get(("B", HEADLINE_SIZE))
    a = cells.get(("A", HEADLINE_SIZE))
    c = cells.get(("C", HEADLINE_SIZE))
    if not (a and b and c):
        return ["# Headline", "", f"Not available: arms A, B and C need counted runs at {HEADLINE_SIZE} students."]

    b_med, a_med = b.median_rate(), a.median_rate()
    m_a, m_b = model["A"]["cohort_rate"], model["B"]["cohort_rate"]
    model_count_b = model["B"]["count_per_1000_course"]
    ratio_b = b_med / m_b if m_b else None
    expected_b = model_count_b * ratio_b if ratio_b is not None else None
    b_counts = [r.misjudged for r in b.runs]
    b_on_time = [int(r.conf["trulyOnTime"]) for r in b.runs]
    c_runs = [r for r in runs if r.arm == "C"]
    c_cells = sum(r.error_cells for r in c_runs)
    c_p95 = c.median_of(lambda r: r.commit("p95"))
    c_p99 = c.median_of(lambda r: r.commit("p99"))
    fallback = b_med < FALLBACK_RATE

    line_b = (f"- **Arm B (stamp at finalize), median cohort misclassification at {HEADLINE_SIZE:,} students: "
              f"{pct(b_med)}** ({len(b.runs)} runs, min-max {pct(min(b.rates()))}-{pct(max(b.rates()))}; "
              f"misjudged {', '.join(map(str, b_counts))} of {', '.join(map(str, b_on_time))} truly on time). "
              f"This is THE number for the repeatable sentence and the resume bullet. `{runs_glob('B', HEADLINE_SIZE)}`")
    line_count = (f"- Expected on-time students misjudged by arm B per 1,000-student course: **{expected_b:.1f}**. "
                  f"Method: the model's full-curve count per 1,000-student course at {MODEL_SPEED} ({model_count_b:.1f}, "
                  f"which assumes one third of the course submits in the final 10 minutes) multiplied by measured/model "
                  f"cohort rate for B at {HEADLINE_SIZE:,} ({pct(b_med)} / {pct(m_b)} = {ratio_b:.2f}). "
                  f"`{src_model}`, `{runs_glob('B', HEADLINE_SIZE)}`")
    line_c = (f"- Arm C (the hash commitment) error cells, onTime_lateOrMissing + late_onTime summed over all "
              f"{len(c_runs)} counted arm C runs: **{c_cells}**. `{runs_glob('C')}`")
    line_ablation = (f"- B-vs-C ablation at {HEADLINE_SIZE:,}: same code, same schedule, same network; only the stamp "
                     f"moves from finalize (B, {pct(b_med)}) to commit arrival (C, {pct(c.median_rate())}). "
                     f"`{runs_glob('B', HEADLINE_SIZE)}`, `{runs_glob('C', HEADLINE_SIZE)}`")

    out += ["# Headline", "",
            "Generated by `tools/summary.py` (`make charts`) from counted runs. Never edit by hand.", ""]
    if fallback:
        out += [f"Arm B's cohort rate ({pct(b_med)}) is under {FALLBACK_RATE:.0%}, so the lead follows the fallback "
                f"rule in PREDICTIONS.md (R82): absolute count per 1,000-student course and the B-vs-C ablation "
                f"first; the cohort is not widened. `PREDICTIONS.md`", "",
                "## Lead", "", line_count, line_ablation, line_c, "", "## Rates", "", line_b]
    else:
        out += ["## Lead", "", line_b, line_ablation, line_c, line_count]
    out += [
        f"- Arm A (bytes through the app), median cohort misclassification at {HEADLINE_SIZE:,}: {pct(a_med)} "
        f"({len(a.runs)} runs, min-max {pct(min(a.rates()))}-{pct(max(a.rates()))}). `{runs_glob('A', HEADLINE_SIZE)}`",
        "",
        "## Against the model (pre-registered, PREDICTIONS.md addendum 2)",
        "",
        f"- Model at {MODEL_SPEED}: A {pct(m_a)}, B {pct(m_b)}, C {pct(model['C']['cohort_rate'])}. `{src_model}`",
        f"- Measured minus model at {HEADLINE_SIZE:,}: A {(a_med - m_a) * 100:+.2f} points, "
        f"B {(b_med - m_b) * 100:+.2f} points. `{runs_glob('A', HEADLINE_SIZE)}`, `{runs_glob('B', HEADLINE_SIZE)}`, `{src_model}`",
        "",
        "## Arm C latency",
        "",
        f"- Arm C commit latency at {HEADLINE_SIZE:,} (median over {len(c.runs)} runs of each run's percentile): "
        f"p95 {ms(c_p95)}, p99 {ms(c_p99)} (target {COMMIT_TARGET_MS} ms). `{runs_glob('C', HEADLINE_SIZE)}`",
        "",
        "## Scaling (arm A at 10 instances vs 3)",
        "",
    ]
    scale = [r for r in variants if r.arm == "A" and r.size == HEADLINE_SIZE]
    if not scale:
        out.append(f"- No counted scale<N> run of arm A at {HEADLINE_SIZE:,}. `results/runs/`")
    for r in scale:
        base = next((x for x in a.runs if int(x.meta["repeat"]) == int(r.meta["repeat"])), None)
        src = f"`{rel(r.folder / 'confusion.json', root)}`"
        log = r.folder / "cloudsql-errors.txt"
        if base is None:
            out.append(f"- Arm A {r.fault}: {pct(r.rate)}; no 3-instance run with the same schedule to compare. {src}")
            continue
        out.append(
            f"- Arm A at {r.meta.get('maxInstances')} instances x pool {r.meta.get('pool')}: {pct(r.rate)} "
            f"({r.misjudged} misjudged, {r.conf['connectionErrors']['responses']} connection errors, commit p95 {ms(r.commit('p95'))}) "
            f"vs {base.meta.get('maxInstances')} instances x pool {base.meta.get('pool')} on the same schedule "
            f"(seed {base.meta.get('scheduleSeed')}): {pct(base.rate)} ({base.misjudged} misjudged, "
            f"{base.conf['connectionErrors']['responses']} connection errors, commit p95 {ms(base.commit('p95'))}). "
            f"{src}, `{rel(base.folder / 'confusion.json', root)}`")
        if log.exists():
            n = sum(1 for l in log.read_text(encoding="utf-8").splitlines() if "remaining connection slots" in l)
            out.append(f"- Cloud SQL \"remaining connection slots are reserved\" lines during that run: {n}. "
                       f"`{rel(log, root)}`")
    return out


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--results", default="results", help="results folder (default: results)")
    args = p.parse_args(argv)
    results = Path(args.results)
    root = results.resolve().parent
    runs = load_runs(results / "runs")
    if not runs:
        print("summary.py: no counted, analysed runs found", file=sys.stderr)
        return 1
    cells = cells_of(runs)
    variants = [r for r in runs if r.fault.startswith("scale")]
    s = write_summary(results, cells, variants)
    h = results / "headline.md"
    h.write_text("\n".join(headline(results, root, runs, cells, variants)) + "\n", encoding="utf-8")
    print(f"wrote {s} and {h} ({len(runs)} counted runs)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
