#!/usr/bin/env python3
"""Draw the sweep charts into results/charts/ (one 1600x900 PNG each).

Usage: python tools/charts.py [--results results]

Reads the same counted runs as tools/summary.py (counted, not invalid, analysed; sweep runs only,
fault "none") and results/model/model-output.json. Charts:
  model-vs-measured.png   misclassification vs cohort size: model at 1x as a line with its 0.5x-2x
                          band, measured medians with min-max whiskers (A, B); C measured
  headline-b-vs-c.png     misclassification at 1,000 students: B and C, A as a lighter third bar
  sweep-abc.png           misclassification vs cohort size, all arms
  commit-latency.png      arm C commit p50/p95/p99 vs cohort size, 500 ms target dashed
  latency-breakdown.png   arm C Server-Timing medians (db, sign, other in-app) vs size, stacked
  confusion-heatmap.png   arm B and arm C at 1,000 students, truth x recorded, summed over repeats
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FuncFormatter  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import summary  # noqa: E402

HEADLINE_SIZE = summary.HEADLINE_SIZE
COMMIT_TARGET_MS = summary.COMMIT_TARGET_MS
WIDTH_PX, HEIGHT_PX, DPI = 1600, 900, 100

# Reference palette (dataviz skill, light mode), categorical slots 1-3 in fixed order: these three
# validate all-pairs for colour-vision deficiency. Text never wears a series colour.
SURFACE = "#fcfcfb"
TEXT = "#0b0b0b"
TEXT_2 = "#52514e"
GRID = "#e4e3df"
ARM_COLOR = {"A": "#2a78d6", "B": "#eb6834", "C": "#1baf7a"}
ARM_NAME = {
    "A": "A: bytes through the app",
    "B": "B: stamp at finalize",
    "C": "C: hash commitment, stamp on arrival",
}
COHORT_NOTE = "public Indian network profiles: slow 7 / medium 20 / fast 54 Mbit/s"

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "text.color": TEXT, "axes.labelcolor": TEXT, "xtick.color": TEXT_2, "ytick.color": TEXT_2,
    "axes.edgecolor": GRID, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 1,
    "axes.spines.top": False, "axes.spines.right": False,
    "font.size": 18, "axes.titlesize": 22, "axes.labelsize": 18, "legend.fontsize": 16,
    "legend.frameon": False, "lines.linewidth": 2, "lines.markersize": 10,
})
PCT = FuncFormatter(lambda v, _: f"{v:.1f}%")


def new_fig():
    fig, ax = plt.subplots(figsize=(WIDTH_PX / DPI, HEIGHT_PX / DPI), dpi=DPI)
    ax.set_axisbelow(True)
    return fig, ax


def save(fig, out: Path, name: str) -> Path:
    path = out / name
    fig.tight_layout()
    fig.savefig(path, dpi=DPI)
    plt.close(fig)
    return path


def sizes_of(cells, arm):
    return sorted(s for (a, s) in cells if a == arm)


def size_axis(ax, sizes):
    ax.set_xscale("log")
    ax.set_xticks(sizes)
    ax.set_xticklabels([f"{s:,}" for s in sizes])
    ax.minorticks_off()
    ax.set_xlabel("Cohort size (students per run, log scale)")


def rate_points(cells, arm):
    xs, med, lo, hi = [], [], [], []
    for s in sizes_of(cells, arm):
        r = [x * 100 for x in cells[(arm, s)].rates()]
        xs.append(s)
        med.append(statistics.median(r))
        lo.append(statistics.median(r) - min(r))
        hi.append(max(r) - statistics.median(r))
    return xs, med, lo, hi


# Small horizontal offsets on the log axis so A and B whiskers at the same size do not overlap.
OFFSET = {"A": 0.96, "B": 1.04, "C": 1.0}


def model_vs_measured(cells, model, out: Path) -> Path:
    fig, ax = new_fig()
    all_sizes = sorted({s for (_, s) in cells})
    lo_x, hi_x = min(all_sizes) * 0.8, max(all_sizes) * 1.25
    for arm in ("A", "B"):
        m1 = model["1x"]["arms"][arm]["cohort_rate"] * 100
        m_slow = model["0.5x"]["arms"][arm]["cohort_rate"] * 100
        m_fast = model["2x"]["arms"][arm]["cohort_rate"] * 100
        col = ARM_COLOR[arm]
        ax.fill_between([lo_x, hi_x], m_fast, m_slow, color=col, alpha=0.06, linewidth=0)
        for edge in (m_fast, m_slow):
            ax.plot([lo_x, hi_x], [edge, edge], color=col, linewidth=1, alpha=0.5)
        ax.plot([lo_x, hi_x], [m1, m1], color=col, linestyle=(0, (6, 3)),
                label=f"{arm} model at 1x ({m1:.2f}%); band 0.5x-2x speed ({m_fast:.2f}-{m_slow:.2f}%)")
        xs, med, lo, hi = rate_points(cells, arm)
        ax.errorbar([x * OFFSET[arm] for x in xs], med, yerr=[lo, hi], fmt="o", color=col, ecolor=col,
                    elinewidth=2, capsize=6, markeredgecolor=SURFACE, markeredgewidth=2,
                    label=f"{arm} measured: median, min-max whiskers")
    xs, med, _, _ = rate_points(cells, "C")
    ax.plot(xs, med, color=ARM_COLOR["C"], marker="o", markeredgecolor=SURFACE, markeredgewidth=2,
            label="C measured (model: 0 at every speed)")
    size_axis(ax, all_sizes)
    ax.set_xlim(lo_x, hi_x)
    ax.set_ylim(bottom=-0.2)
    ax.yaxis.set_major_formatter(PCT)
    ax.set_ylabel("On-time students misjudged (% of truly on time)")
    ax.set_title("Model vs measured misclassification by cohort size (A and B; C at 0)")
    ax.legend(loc="upper left")
    return save(fig, out, "model-vs-measured.png")


def headline_b_vs_c(cells, out: Path) -> Path:
    fig, ax = new_fig()
    bars = [("B", 1.0), ("C", 1.0), ("A", 0.35)]
    labels, vals = [], []
    for i, (arm, alpha) in enumerate(bars):
        c = cells[(arm, HEADLINE_SIZE)]
        v = c.median_rate() * 100
        lo, hi = min(c.rates()) * 100, max(c.rates()) * 100
        ax.bar(i, v, width=0.55, color=ARM_COLOR[arm], alpha=alpha, edgecolor=SURFACE, linewidth=2)
        ax.errorbar(i, v, yerr=[[v - lo], [hi - v]], fmt="none", ecolor=TEXT_2, elinewidth=2, capsize=8)
        ax.annotate(f"{v:.2f}%\n(min-max {lo:.2f}-{hi:.2f}%, {len(c.runs)} runs)", (i, hi),
                    xytext=(0, 10), textcoords="offset points", ha="center", va="bottom", color=TEXT, fontsize=16)
        labels.append(ARM_NAME[arm])
        vals.append(hi)
    ax.set_xticks(range(len(bars)))
    ax.set_xticklabels(labels)
    ax.set_ylim(0, max(max(vals) * 1.35, 1.0))
    ax.yaxis.set_major_formatter(PCT)
    ax.set_ylabel("On-time students misjudged (% of truly on time, median)")
    ax.grid(axis="x", visible=False)
    ax.set_title(f"Misjudged on-time students, {HEADLINE_SIZE:,}-student cohort: B vs C (A lighter, for reference)\n"
                 f"{COHORT_NOTE}", fontsize=20)
    return save(fig, out, "headline-b-vs-c.png")


def sweep_abc(cells, out: Path) -> Path:
    fig, ax = new_fig()
    all_sizes = sorted({s for (_, s) in cells})
    for arm in ("A", "B", "C"):
        xs, med, lo, hi = rate_points(cells, arm)
        ax.errorbar([x * OFFSET[arm] for x in xs], med, yerr=[lo, hi], color=ARM_COLOR[arm], ecolor=ARM_COLOR[arm],
                    marker="o", markeredgecolor=SURFACE, markeredgewidth=2, capsize=6, elinewidth=2,
                    label=ARM_NAME[arm])
    size_axis(ax, all_sizes)
    ax.set_ylim(bottom=-0.2)
    ax.yaxis.set_major_formatter(PCT)
    ax.set_ylabel("On-time students misjudged (% of truly on time)")
    ax.set_title("Misclassification vs cohort size, all arms (median, min-max whiskers)")
    ax.legend(loc="upper left")
    return save(fig, out, "sweep-abc.png")


def commit_latency(cells, out: Path) -> Path:
    fig, ax = new_fig()
    sizes = sizes_of(cells, "C")
    # Three percentiles of one series: one hue, line style carries identity, plus direct labels.
    styles = {"p50": (0, (1, 2)), "p95": "solid", "p99": (0, (6, 3))}
    for q, ls in styles.items():
        ys = [cells[("C", s)].median_of(lambda r, q=q: r.commit(q)) for s in sizes]
        ax.plot(sizes, ys, color=ARM_COLOR["C"], linestyle=ls, marker="o", markeredgecolor=SURFACE,
                markeredgewidth=2, label=f"arm C commit {q}")
        ax.annotate(q, (sizes[-1], ys[-1]), xytext=(10, 0), textcoords="offset points", va="center", color=TEXT)
    ax.axhline(COMMIT_TARGET_MS, color=TEXT_2, linestyle=(0, (4, 4)), linewidth=2,
               label=f"{COMMIT_TARGET_MS} ms target")
    size_axis(ax, sizes)
    ax.set_ylim(0, COMMIT_TARGET_MS * 1.2)
    ax.set_ylabel("Commit latency seen by k6 (ms, median over repeats)")
    ax.set_title("Arm C commit latency vs cohort size")
    ax.legend(loc="upper right", ncol=2)
    return save(fig, out, "commit-latency.png")


def latency_breakdown(cells, out: Path) -> Path:
    fig, ax = new_fig()
    sizes = sizes_of(cells, "C")
    phases = [("db", "database", "#2a78d6"), ("sign", "URL signing (IAM signBlob)", "#eb6834"),
              ("other", "other in-app time", "#1baf7a")]
    xs = list(range(len(sizes)))
    bottom = [0.0] * len(sizes)

    def st(r, k):
        v = r.conf.get("serverTimingMedianMs") or {}
        return v.get(k)

    for key, label, col in phases:
        vals = [cells[("C", s)].median_of(lambda r, k=key: st(r, k)) or 0.0 for s in sizes]
        ax.bar(xs, vals, bottom=bottom, width=0.55, color=col, edgecolor=SURFACE, linewidth=2, label=label)
        bottom = [b + v for b, v in zip(bottom, vals)]
    app = [cells[("C", s)].median_of(lambda r: st(r, "app")) for s in sizes]
    ax.plot(xs, app, color=TEXT, marker="D", linestyle="none", markersize=10, label="app (whole request), median")
    for x, a in zip(xs, app):
        if a is not None:
            ax.annotate(f"{a:.0f} ms", (x, a), xytext=(0, 12), textcoords="offset points", ha="center", color=TEXT)
    ax.set_xticks(xs)
    ax.set_xticklabels([f"{s:,}" for s in sizes])
    ax.set_xlabel("Cohort size (students per run)")
    ax.set_ylabel("Server-Timing per commit (ms, median over repeats)")
    ax.set_ylim(0, max([a for a in app if a] + bottom) * 1.5)
    ax.grid(axis="x", visible=False)
    ax.set_title("Arm C commit: where the server time goes (stacked medians)")
    ax.legend(loc="upper right", ncol=2)
    return save(fig, out, "latency-breakdown.png")


def confusion_heatmap(cells, out: Path) -> Path:
    fig, axes = plt.subplots(1, 2, figsize=(WIDTH_PX / DPI, HEIGHT_PX / DPI), dpi=DPI)
    keys = [("onTime_onTime", "onTime_lateOrMissing"), ("late_onTime", "late_late")]
    mats = {}
    for arm in ("B", "C"):
        runs = cells[(arm, HEADLINE_SIZE)].runs
        mats[arm] = [[sum(int(r.conf[k]) for r in runs) for k in row] for row in keys]
    vmax = max(v for m in mats.values() for row in m for v in row)
    for ax, arm in zip(axes, ("B", "C")):
        m = mats[arm]
        ax.imshow(m, cmap="Blues", vmin=0, vmax=vmax)
        for i in range(2):
            for j in range(2):
                v = m[i][j]
                ax.text(j, i, f"{v:,}", ha="center", va="center", fontsize=26,
                        color=SURFACE if v > vmax * 0.55 else TEXT)
        ax.set_xticks([0, 1])
        ax.set_xticklabels(["recorded on time", "recorded late\nor missing"])
        ax.set_yticks([0, 1])
        ax.set_yticklabels(["truly on time", "truly late"])
        ax.grid(False)
        for s in ax.spines.values():
            s.set_visible(False)
        n = len(cells[(arm, HEADLINE_SIZE)].runs)
        ax.set_title(f"{ARM_NAME[arm]}\n({n} runs summed; margin students excluded)", fontsize=18)
    fig.suptitle(f"Truth (k6 click time) vs recorded outcome, {HEADLINE_SIZE:,}-student cohort (students)", fontsize=22)
    return save(fig, out, "confusion-heatmap.png")


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--results", default="results", help="results folder (default: results)")
    args = p.parse_args(argv)
    results = Path(args.results)
    runs = summary.load_runs(results / "runs")
    cells = summary.cells_of(runs)
    missing = [a for a in ("A", "B", "C") if (a, HEADLINE_SIZE) not in cells]
    if missing:
        print(f"charts.py: no counted sweep runs at {HEADLINE_SIZE} students for arm(s) {', '.join(missing)}",
              file=sys.stderr)
        return 1
    model = json.loads((results / "model" / "model-output.json").read_text(encoding="utf-8"))["results"]
    out = results / "charts"
    out.mkdir(parents=True, exist_ok=True)
    for path in (model_vs_measured(cells, model, out), headline_b_vs_c(cells, out), sweep_abc(cells, out),
                 commit_latency(cells, out), latency_breakdown(cells, out), confusion_heatmap(cells, out)):
        print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
