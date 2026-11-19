#!/usr/bin/env python3
"""Analytic model of misjudged on-time students for arms A, B and C.

For a student who clicks c seconds before the deadline with a file of S bytes, upload
rate U bits/s, round trip r seconds and hash time h seconds:

    late_A = c < 8*S/U + r        stamped when the multipart body has fully arrived
    late_B = c < 8*S/U + 3*r      stamped at finalize, after the PUT lands
    late_C = c < h + r            stamped when the commit arrives, after hashing

Rates are taken over the truly-on-time set (c >= 5 s). Clicks in [0, 5) s are the margin
and are reported separately. There is no student pilot (docs/decisions.md D8), so the
inputs are the public network profiles plus stated assumptions; every assumption is
written into the output with its reason.

Usage:
    python tools/model/model.py --profiles results/network/public-profiles.csv --out results/model
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import gen_schedule as gs  # noqa: E402  the model uses the schedule's own constants

ON_TIME_S = 5.0
SPEED_MULTIPLIERS = (1.0, 0.5, 2.0)
ARMS = ("A", "B", "C")
DEFAULT_SEED = 20261119
DEFAULT_SAMPLES = 10_000
COURSE_SIZE = 1000
COURSE_SHARE_IN_WINDOW = 1.0 / 3.0
MIB = 1024 * 1024

# Public-profile rows each cohort value is taken from: (segment, metric, statistic).
PROFILE_CITATIONS = {
    "slow": {"rate": [("mobile", "upload", "p20")], "rtt": [("mobile", "latency", "p80")]},
    "medium": {"rate": [("mobile", "upload", "p80"), ("fixed", "upload", "p20")],
               "rtt": [("mobile", "latency", "p50"), ("fixed", "latency", "p80")]},
    "fast": {"rate": [("fixed", "upload", "p50")], "rtt": [("fixed", "latency", "p50")]},
}
TILE_SOURCE = "Ookla open data tiles (India box)"


# ---------------------------------------------------------------------------------------
# N rule
# ---------------------------------------------------------------------------------------

def choose_n(minutes_before_deadline: list[float]) -> tuple[int, str]:
    """N = smallest whole minute count holding >= 1/3 of submissions, clamped to 5-15.

    With fewer than 30 submissions, N = 10.
    """
    total = len(minutes_before_deadline)
    if total < 30:
        return 10, f"{total} pilot submissions (< 30), so N = 10"
    ordered = sorted(minutes_before_deadline)
    needed = math.ceil(total / 3)
    n = max(1, math.ceil(ordered[needed - 1]))
    clamped = min(15, max(5, n))
    return clamped, f"{total} submissions; final {n} min hold >= 1/3; clamped to {clamped}"


# ---------------------------------------------------------------------------------------
# Inputs
# ---------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Cohort:
    name: str
    weight: float
    rate_mbps: float
    rtt_ms: float


@dataclass(frozen=True)
class Inputs:
    cohorts: tuple[Cohort, ...]
    sizes_mib: tuple[int, ...]
    size_probs: tuple[float, ...]
    hash_mib_per_s: float
    window_min: float
    n_students: int = 1000
    late_frac: float = 0.10
    course_size: int = COURSE_SIZE
    course_share: float = COURSE_SHARE_IN_WINDOW
    citations: dict = field(default_factory=dict, compare=False)

    @property
    def window_s(self) -> float:
        return self.window_min * 60.0

    @property
    def beta(self) -> float:
        return gs.rush_beta(self.window_min)


def read_profiles_csv(path: Path) -> list[dict]:
    with path.open(encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def cite_profiles(rows: list[dict]) -> dict:
    """Look up every cited row, and check each cohort value sits within its cited values.

    Tolerance: max(1 unit, 5%) beyond the cited range, so rounding (7.124 -> 7 Mbps,
    9 -> 10 ms) passes but a drifted profile or CSV fails loudly.
    """
    index = {(r["segment"], r["metric"], r["statistic"]): r
             for r in rows if r["source"] == TILE_SOURCE and r["value"]}
    cited = {}
    for cohort, parts in PROFILE_CITATIONS.items():
        profile = gs.PUBLIC_PROFILES[cohort]
        cited[cohort] = {}
        for part, keys in parts.items():
            value = profile["rateMbps"] if part == "rate" else profile["rttMs"]
            found = []
            for key in keys:
                if key not in index:
                    raise ValueError(f"{cohort} {part}: cited row {key} missing from public-profiles.csv")
                found.append(index[key])
            numbers = [float(r["value"]) for r in found]
            tol = max(1.0, 0.05 * max(numbers))
            if not (min(numbers) - tol <= value <= max(numbers) + tol):
                raise ValueError(f"{cohort} {part} = {value} is outside its cited values {numbers}")
            cited[cohort][part] = {
                "value": value,
                "unit": "Mbps" if part == "rate" else "ms",
                "cited": [{"segment": r["segment"], "metric": r["metric"], "statistic": r["statistic"],
                           "value": float(r["value"]), "unit": r["unit"], "period": r["period"],
                           "url": r["url"]} for r in found],
            }
    return cited


def public_profile_inputs(profiles_csv: Path, window_min: float) -> Inputs:
    citations = cite_profiles(read_profiles_csv(profiles_csv))
    cohorts = tuple(
        Cohort(name, weight, gs.PUBLIC_PROFILES[name]["rateMbps"], gs.PUBLIC_PROFILES[name]["rttMs"])
        for name, weight in gs.parse_cohorts(gs.DEFAULT_COHORTS))
    sizes, probs = gs.size_distribution(max(gs.SIZE_BUCKETS_MB))
    return Inputs(cohorts=cohorts, sizes_mib=tuple(sizes), size_probs=tuple(probs),
                  hash_mib_per_s=gs.HASH_RATE_MB_PER_S, window_min=window_min, citations=citations)


# ---------------------------------------------------------------------------------------
# The three rules
# ---------------------------------------------------------------------------------------

def late_flags(c_s, size_bytes, rate_bps, rtt_s, hash_s) -> dict[str, np.ndarray]:
    transfer_s = 8.0 * np.asarray(size_bytes, dtype=float) / np.asarray(rate_bps, dtype=float)
    c_s = np.asarray(c_s, dtype=float)
    rtt_s = np.asarray(rtt_s, dtype=float)
    return {
        "A": c_s < transfer_s + rtt_s,
        "B": c_s < transfer_s + 3.0 * rtt_s,
        "C": c_s < np.asarray(hash_s, dtype=float) + rtt_s,
    }


def curve_cdf(t_s, window_s: float, beta: float):
    """P(click is less than t seconds before the deadline), for the convex rush curve."""
    t = np.clip(np.asarray(t_s, dtype=float) / window_s, 0.0, 1.0)
    return 1.0 - np.power(1.0 - t, beta)


def sample_click_s(rng: np.random.Generator, shape, window_s: float, beta: float) -> np.ndarray:
    """Seconds before the deadline, drawn exactly as gen_schedule draws cohort offsets."""
    u = rng.uniform(0.0001, 0.9999, size=shape)
    return np.maximum(window_s * (1.0 - np.power(u, 1.0 / beta)), 0.001)


def threshold_s(arm: str, size_mib: float, rate_mbps: float, rtt_ms: float, hash_mib_per_s: float) -> float:
    transfer = 8.0 * size_mib * MIB / (rate_mbps * 1e6)
    r = rtt_ms / 1000.0
    return {"A": transfer + r, "B": transfer + 3.0 * r, "C": size_mib / hash_mib_per_s + r}[arm]


# ---------------------------------------------------------------------------------------
# Exact expectation
# ---------------------------------------------------------------------------------------

def expected(inputs: Inputs, speed: float) -> dict:
    """Closed form over the discrete cohort x size mix and the continuous click curve."""
    w, beta = inputs.window_s, inputs.beta
    f5 = float(curve_cdf(ON_TIME_S, w, beta))
    on_time_mass = 1.0 - f5
    out = {"margin_share_of_cohort": f5, "arms": {}}
    for arm in ARMS:
        on_time_late = 0.0
        margin_late = 0.0
        per_cohort = {}
        for co in inputs.cohorts:
            co_on, co_margin = 0.0, 0.0
            for size, p in zip(inputs.sizes_mib, inputs.size_probs):
                t = threshold_s(arm, size, co.rate_mbps * speed, co.rtt_ms, inputs.hash_mib_per_s)
                ft = float(curve_cdf(t, w, beta))
                co_on += p * max(0.0, ft - f5)
                co_margin += p * min(ft, f5)
            per_cohort[co.name] = co_on / on_time_mass
            on_time_late += co.weight * co_on
            margin_late += co.weight * co_margin
        rate = on_time_late / on_time_mass
        out["arms"][arm] = {
            "cohort_rate": rate,
            "cohort_rate_by_network": per_cohort,
            "margin_late_rate": (margin_late / f5) if f5 > 0 else None,
            "count_per_1000_course": inputs.course_size * inputs.course_share * on_time_late,
        }
    return out


# ---------------------------------------------------------------------------------------
# Monte Carlo: spread of one 1,000-student run and one 1,000-student course
# ---------------------------------------------------------------------------------------

def monte_carlo(inputs: Inputs, samples: int, seed: int, chunk: int = 500) -> dict:
    rng = np.random.default_rng(seed)
    late_count = max(int(math.ceil(inputs.n_students * inputs.late_frac)), 1) if inputs.late_frac > 0 else 0
    cohort_n = inputs.n_students - late_count
    weights = np.array([co.weight for co in inputs.cohorts])
    weights = weights / weights.sum()
    rate_bps = np.array([co.rate_mbps * 1e6 for co in inputs.cohorts])
    rtt_s = np.array([co.rtt_ms / 1000.0 for co in inputs.cohorts])
    sizes = np.array(inputs.sizes_mib, dtype=float)
    size_p = np.array(inputs.size_probs)
    size_p = size_p / size_p.sum()

    run_rates = {(s, a): [] for s in SPEED_MULTIPLIERS for a in ARMS}
    course_counts = {(s, a): [] for s in SPEED_MULTIPLIERS for a in ARMS}

    done = 0
    while done < samples:
        k = min(chunk, samples - done)
        # (1) one load run: cohort_n students on the rush curve (late students never count)
        c = sample_click_s(rng, (k, cohort_n), inputs.window_s, inputs.beta)
        co = rng.choice(len(weights), p=weights, size=(k, cohort_n))
        sz = sizes[rng.choice(len(sizes), p=size_p, size=(k, cohort_n))]
        on_time = c >= ON_TIME_S
        denom = on_time.sum(axis=1)
        # (2) one course: each student is in the final-N window with probability course_share
        cc = sample_click_s(rng, (k, inputs.course_size), inputs.window_s, inputs.beta)
        cco = rng.choice(len(weights), p=weights, size=(k, inputs.course_size))
        csz = sizes[rng.choice(len(sizes), p=size_p, size=(k, inputs.course_size))]
        in_window = rng.uniform(size=(k, inputs.course_size)) < inputs.course_share
        course_on_time = in_window & (cc >= ON_TIME_S)
        for s in SPEED_MULTIPLIERS:
            flags = late_flags(c, sz * MIB, rate_bps[co] * s, rtt_s[co], sz / inputs.hash_mib_per_s)
            cflags = late_flags(cc, csz * MIB, rate_bps[cco] * s, rtt_s[cco], csz / inputs.hash_mib_per_s)
            for a in ARMS:
                run_rates[(s, a)].append((flags[a] & on_time).sum(axis=1) / denom)
                course_counts[(s, a)].append((cflags[a] & course_on_time).sum(axis=1))
        done += k

    def summary(values) -> dict:
        v = np.concatenate(values).astype(float)
        p025, p50, p975 = np.percentile(v, [2.5, 50, 97.5])
        return {"mean": float(v.mean()), "p2_5": float(p025), "p50": float(p50), "p97_5": float(p975)}

    return {(s, a): {"cohort_rate": summary(run_rates[(s, a)]),
                     "count_per_1000_course": summary(course_counts[(s, a)])}
            for s in SPEED_MULTIPLIERS for a in ARMS}


# ---------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------

def longest_transfer_s(inputs: Inputs, speed: float) -> float:
    return max(threshold_s("B", max(inputs.sizes_mib), co.rate_mbps * speed, co.rtt_ms, inputs.hash_mib_per_s)
               for co in inputs.cohorts)


def assumptions(inputs: Inputs, n_reason: str) -> list[dict]:
    weights = ", ".join(f"{co.name} {co.weight:g}" for co in inputs.cohorts)
    sizes = ", ".join(f"{s} MiB {p:.0%}" for s, p in zip(inputs.sizes_mib, inputs.size_probs))
    slowest = min(SPEED_MULTIPLIERS)
    return [
        {"name": "cohort upload rate and RTT",
         "value": {co.name: {"rate_mbps": co.rate_mbps, "rtt_ms": co.rtt_ms} for co in inputs.cohorts},
         "reason": "Taken from published Indian measurements (docs/network-profiles.md); the k6 VMs are shaped "
                   "to the same values, so the model and the run see the same links. One rate per cohort, no "
                   "spread inside it, because the shaper gives every student in a cohort the same link."},
        {"name": "cohort weights",
         "value": weights,
         "reason": "No public source gives the network mix of students at a deadline. These are the weights the "
                   "dry runs already used (tools/gen_schedule.py DEFAULT_COHORTS), kept so the dry runs and the "
                   "counted runs replay the same mix; most weight sits on the two mobile-like cohorts."},
        {"name": "file-size mix",
         "value": sizes,
         "reason": "The schedule's size buckets (tools/gen_schedule.py) and the files make-files.sh writes "
                   "(1 MiB = 1,048,576 bytes). No public source gives assignment file sizes; the mix centres on "
                   "5 MiB (a report with images) with a 25 MiB tail under the 25 MB cap."},
        {"name": "hash time",
         "value": f"size / {inputs.hash_mib_per_s:g} MiB/s",
         "reason": "Web Crypto SHA-256 in a mid-range phone browser; the k6 script sleeps exactly this long "
                   "before the arm C commit, so the model matches what the run does."},
        {"name": "click-time curve",
         "value": f"convex: P(t < x) = 1 - (1 - x/W)^beta, W = {inputs.window_s:g} s, beta = {inputs.beta:.4f}; "
                  "40% of the cohort in the final 60 s",
         "reason": "No pilot curve exists (D8). This is the curve tools/gen_schedule.py already draws from, so "
                   "the model predicts the schedule the runs replay."},
        {"name": "N (cohort window, minutes)",
         "value": inputs.window_min,
         "reason": n_reason},
        {"name": "share of a course in the final N minutes",
         "value": inputs.course_share,
         "reason": "No whole-course arrival curve exists. One third is the share the pre-registered N rule aims "
                   "for (the smallest window holding at least a third of submissions). The count scales "
                   "linearly: count = 1,000 x share x P(on time and misjudged)."},
        {"name": "submissions before the final N minutes are never misjudged",
         "value": f"longest arm B threshold at {slowest:g}x speed = {longest_transfer_s(inputs, slowest):.1f} s "
                  f"< {inputs.window_s:g} s",
         "reason": "Checked at startup: a click earlier than N minutes leaves more time than the slowest "
                   "transfer needs."},
        {"name": "what the rules leave out",
         "value": "TCP slow start, header overhead, retries, queueing, cold starts, Cloud Run buffering",
         "reason": "The model is the no-load floor. Measured arm A minus the model is the load-induced error."},
        {"name": "speed multiplier stands in for the click-curve test",
         "value": "0.5x and 2x",
         "reason": "Stretching every click offset by a is the same as multiplying every rate by a with RTT "
                   "divided by a (c*a < 8S/U + r  <=>  c < 8S/(aU) + r/a); RTT is small next to transfer time, "
                   "so the speed range also covers D8's 0.5x / 2x click-curve test."},
    ]


def build_output(inputs: Inputs, n: int, n_reason: str, samples: int, seed: int, profiles_csv: str) -> dict:
    if longest_transfer_s(inputs, min(SPEED_MULTIPLIERS)) >= inputs.window_s:
        raise ValueError("the slowest transfer is longer than the cohort window; the course count would be wrong")
    mc = monte_carlo(inputs, samples, seed)
    results = {}
    for s in SPEED_MULTIPLIERS:
        ex = expected(inputs, s)
        results[f"{s:g}x"] = {
            "speed_multiplier": s,
            "margin_share_of_cohort": ex["margin_share_of_cohort"],
            "arms": {a: {**ex["arms"][a],
                         "run_of_1000": mc[(s, a)]["cohort_rate"],
                         "course_of_1000": mc[(s, a)]["count_per_1000_course"]} for a in ARMS},
        }
    return {
        "model": "tools/model/model.py",
        "inputs_mode": "PUBLIC_PROFILES",
        "pilot": None,
        "N_minutes": n,
        "N_rule": "N = smallest whole number of minutes before the deadline containing at least one third of all "
                  "pilot submissions, clamped to 5-15; if fewer than 30 submissions exist, N = 10.",
        "N_reason": n_reason,
        "rules": {"late_A": "c < 8*S/U + r", "late_B": "c < 8*S/U + 3*r", "late_C": "c < h + r",
                  "on_time": f"c >= {ON_TIME_S:g} s; [0, {ON_TIME_S:g}) s reported as margin"},
        "seed": seed,
        "samples": samples,
        "run_of_1000_definition": f"{inputs.n_students} students, {inputs.late_frac:.0%} late (excluded), the rest "
                                  f"on the click curve; rate = misjudged / truly on time; spread over {samples} "
                                  "simulated runs",
        "profiles_csv": profiles_csv,
        "profile_citations": inputs.citations,
        "assumptions": assumptions(inputs, n_reason),
        "results": results,
    }


def write_sensitivity(path: Path, output: dict) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["speed_multiplier", "arm", "cohort_rate", "run_p2_5", "run_p97_5", "margin_late_rate",
                    "count_per_1000_course", "course_p2_5", "course_p97_5",
                    *[f"rate_{co}" for co in gs.PUBLIC_PROFILES]])
        for res in output["results"].values():
            for a, v in res["arms"].items():
                w.writerow([res["speed_multiplier"], a, f"{v['cohort_rate']:.4f}",
                            f"{v['run_of_1000']['p2_5']:.4f}", f"{v['run_of_1000']['p97_5']:.4f}",
                            "" if v["margin_late_rate"] is None else f"{v['margin_late_rate']:.4f}",
                            f"{v['count_per_1000_course']:.1f}",
                            f"{v['course_of_1000']['p2_5']:.0f}", f"{v['course_of_1000']['p97_5']:.0f}",
                            *[f"{v['cohort_rate_by_network'].get(co, float('nan')):.4f}" for co in gs.PUBLIC_PROFILES]])


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Analytic model of misjudged on-time students (arms A, B, C)")
    parser.add_argument("--profiles", type=Path, default=Path("results/network/public-profiles.csv"))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=DEFAULT_SAMPLES)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    args = parser.parse_args(argv)

    started = time.perf_counter()
    n, n_reason = choose_n([])  # no pilot arrival curve exists (D8)
    print(f"N = {n} ({n_reason})")
    inputs = public_profile_inputs(args.profiles, n)
    output = build_output(inputs, n, n_reason, args.samples, args.seed, args.profiles.as_posix())

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "model-output.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    write_sensitivity(args.out / "sensitivity.csv", output)

    for res in output["results"].values():
        arms = res["arms"]
        print(f"{res['speed_multiplier']:g}x  " + "  ".join(
            f"{a}: {arms[a]['cohort_rate']:.2%} [{arms[a]['run_of_1000']['p2_5']:.2%}-"
            f"{arms[a]['run_of_1000']['p97_5']:.2%}], {arms[a]['count_per_1000_course']:.1f}/1000"
            for a in ARMS))
    print(f"wrote {args.out / 'model-output.json'} and {args.out / 'sensitivity.csv'} "
          f"in {time.perf_counter() - started:.1f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
