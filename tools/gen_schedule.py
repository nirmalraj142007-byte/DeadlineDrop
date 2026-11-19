#!/usr/bin/env python3
"""Generate load test schedule for DeadlineDrop.

Outputs:
  - shard-<cohort>.json per cohort (array of student records)
  - shard-all.json (all students)
  - schedule-meta.json (seed, counts per class, N, mode)
"""

import argparse
import csv
import json
import math
import os
import sys
from pathlib import Path
import numpy as np

SPEED_MEDIANS_MBPS = {
    "slow": 1.0,
    "medium": 4.0,
    "fast": 20.0,
}

SIZE_BUCKETS_MB = [1, 2, 5, 10, 15, 25]  # MiB, as tools/k6/files/make-files.sh writes them
SIZE_BUCKET_WEIGHTS = [10, 20, 30, 20, 15, 5]
HASH_RATE_MB_PER_S = 80.0
DEFAULT_COHORTS = "slow:0.3,medium:0.4,fast:0.3"

# Cohort profiles from published Indian measurements (docs/network-profiles.md,
# results/network/public-profiles.csv): the rate and delay the k6 VMs are shaped to.
PUBLIC_PROFILES = {
    "slow": {"rateMbps": 7.0, "rttMs": 40.0,
             "source": "Ookla open data 2026-Q3 India: mobile upload p20 7.124 Mbps; mobile latency p80 41 ms"},
    "medium": {"rateMbps": 20.0, "rttMs": 25.0,
               "source": "Ookla open data 2026-Q3 India: mobile upload p80 21.248 and fixed upload p20 18.544 Mbps;"
                         " latency between mobile p50 (29 ms) and fixed p80 (20 ms)"},
    "fast": {"rateMbps": 54.0, "rttMs": 10.0,
             "source": "Ookla open data 2026-Q3 India: fixed upload p50 54.409 Mbps; fixed latency p50 9 ms"},
}

MODE_LABELS = {"placeholder": "PLACEHOLDER", "bootstrap": "BOOTSTRAP", "profiles": "PUBLIC_PROFILES"}


def rush_beta(cohort_minutes: float) -> float:
    """Exponent of the convex rush curve that puts 40% of the cohort in the final 60 s."""
    w_ms = cohort_minutes * 60.0 * 1000.0
    if w_ms > 60000.0:
        return math.log(0.60) / math.log(1.0 - 60000.0 / w_ms)
    return 2.0


def size_distribution(max_mb: int) -> tuple[list[int], list[float]]:
    """Size buckets (MiB) capped at max_mb, with their probabilities."""
    sizes = [s for s in SIZE_BUCKETS_MB if s <= max_mb]
    weights = [w for s, w in zip(SIZE_BUCKETS_MB, SIZE_BUCKET_WEIGHTS) if s <= max_mb]
    if not sizes:
        sizes, weights = [max_mb], [1.0]
    total = sum(weights)
    return sizes, [w / total for w in weights]


def parse_cohorts(cohorts_str: str) -> list[tuple[str, float]]:
    parts = cohorts_str.split(",")
    cohorts = []
    for part in parts:
        name, weight = part.split(":")
        cohorts.append((name.strip(), float(weight.strip())))
    total = sum(w for _, w in cohorts)
    return [(name, w / total) for name, w in cohorts]


def build_rush_schedule(args, rng: np.random.Generator, mode_label: str, speed_for):
    """Synthetic rush: cohorts by weight, size buckets, convex click curve, late tail.

    speed_for(cohort, rng) returns the student's upload rate in Mbps. It is called once per
    student, in order, so it may draw from rng without changing earlier draws.
    """
    cohort_specs = parse_cohorts(args.cohorts)
    cohort_names = [name for name, _ in cohort_specs]
    cohort_probs = [prob for _, prob in cohort_specs]

    available_sizes, size_probs = size_distribution(args.max_mb)

    n = args.n
    late_count = max(int(math.ceil(n * args.late_frac)), 1) if args.late_frac > 0 else 0
    cohort_count = n - late_count

    # Click offsets for cohort students: convex last-minutes curve (40% in final 60 s)
    w_ms = args.cohort_minutes * 60.0 * 1000.0
    beta = rush_beta(args.cohort_minutes)

    u_cohort = rng.uniform(0.0001, 0.9999, size=cohort_count)
    cohort_offsets_ms = -w_ms * (1.0 - np.power(u_cohort, 1.0 / beta))
    # Ensure all cohort offsets are strictly negative
    cohort_offsets_ms = np.minimum(cohort_offsets_ms, -1.0)

    # Click offsets for late students: in [0, grace_s * 1000)
    grace_ms = args.grace_s * 1000.0
    late_offsets_ms = rng.uniform(0.0, max(grace_ms - 100.0, 1.0), size=late_count)

    all_offsets_ms = np.concatenate([cohort_offsets_ms, late_offsets_ms])
    # Assign cohort category randomly based on weights
    cohort_assignments = rng.choice(cohort_names, p=cohort_probs, size=n)
    # Assign file size buckets
    size_assignments = rng.choice(available_sizes, p=size_probs, size=n)

    students = []
    for i in range(n):
        student_num = i + 1
        email = f"{args.students_prefix}{student_num:04d}@dd.local"
        cohort = str(cohort_assignments[i])
        click_offset_ms = int(round(float(all_offsets_ms[i])))
        size_mb = int(size_assignments[i])
        hash_ms = int(round((size_mb / HASH_RATE_MB_PER_S) * 1000.0))

        if click_offset_ms <= -5000:
            planned_class = "ON_TIME"
        elif click_offset_ms < 0:
            planned_class = "MARGIN"
        else:
            planned_class = "LATE"

        speed_mbps = speed_for(cohort, rng)

        skip_finalize = bool(rng.uniform(0.0, 1.0) < args.skip_finalize_frac)

        record = {
            "mode": mode_label,
            "email": email,
            "cohort": cohort,
            "clickOffsetMs": click_offset_ms,
            "sizeBucketMb": size_mb,
            "hashMs": hash_ms,
            "plannedClass": planned_class,
            "skipFinalize": skip_finalize,
            "speedMbps": round(speed_mbps, 3),
        }
        if mode_label == "PUBLIC_PROFILES":
            record["rttMs"] = PUBLIC_PROFILES[cohort]["rttMs"]
        students.append(record)

    return students


def build_placeholder_schedule(args, rng: np.random.Generator):
    def lognormal_speed(cohort, r):
        median_speed = SPEED_MEDIANS_MBPS.get(cohort, 4.0)
        return float(np.exp(r.normal(math.log(median_speed), 0.4)))
    return build_rush_schedule(args, rng, "PLACEHOLDER", lognormal_speed)


def build_profiles_schedule(args, rng: np.random.Generator):
    """PUBLIC_PROFILES: every student gets their cohort's shaped rate and RTT.

    No per-student spread: the k6 VM shapes the whole cohort to one rate, so a per-student
    speed would describe a link the run never had.
    """
    unknown = [name for name, _ in parse_cohorts(args.cohorts) if name not in PUBLIC_PROFILES]
    if unknown:
        raise ValueError(f"No public profile for cohort(s) {unknown}; known: {sorted(PUBLIC_PROFILES)}")
    return build_rush_schedule(args, rng, "PUBLIC_PROFILES",
                               lambda cohort, r: PUBLIC_PROFILES[cohort]["rateMbps"])


def build_bootstrap_schedule(args, rng: np.random.Generator):
    if not args.pilot_csv or not os.path.exists(args.pilot_csv):
        raise ValueError(f"Pilot CSV file not found: {args.pilot_csv}")

    rows = []
    with open(args.pilot_csv, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append(r)

    if not rows:
        raise ValueError(f"Pilot CSV {args.pilot_csv} is empty")

    cohort_specs = parse_cohorts(args.cohorts)
    cohort_names = [name for name, _ in cohort_specs]
    cohort_probs = [prob for _, prob in cohort_specs]

    sample_indices = rng.choice(len(rows), size=args.n, replace=True)
    students = []
    for i, idx in enumerate(sample_indices):
        student_num = i + 1
        email = f"{args.students_prefix}{student_num:04d}@dd.local"
        row = rows[idx]

        size_mb = min(int(row.get("sizeBucketMb", row.get("size_mb", 1))), args.max_mb)
        hash_ms = int(row.get("hashMs", row.get("hash_ms", round((size_mb / HASH_RATE_MB_PER_S) * 1000.0))))
        click_offset_ms = int(row.get("clickOffsetMs", row.get("click_offset_ms", 0)))
        cohort = row.get("cohort", str(rng.choice(cohort_names, p=cohort_probs)))
        speed_mbps = float(row.get("speedMbps", row.get("speed_mbps", 4.0)))

        if click_offset_ms <= -5000:
            planned_class = "ON_TIME"
        elif click_offset_ms < 0:
            planned_class = "MARGIN"
        else:
            planned_class = "LATE"

        skip_finalize = bool(rng.uniform(0.0, 1.0) < args.skip_finalize_frac)

        students.append({
            "mode": "BOOTSTRAP",
            "email": email,
            "cohort": cohort,
            "clickOffsetMs": click_offset_ms,
            "sizeBucketMb": size_mb,
            "hashMs": hash_ms,
            "plannedClass": planned_class,
            "skipFinalize": skip_finalize,
            "speedMbps": round(speed_mbps, 3),
        })

    return students


def main():
    parser = argparse.ArgumentParser(description="Generate DeadlineDrop load schedule")
    parser.add_argument("--mode", choices=["placeholder", "bootstrap", "profiles"], default="placeholder")
    parser.add_argument("--pilot-csv", type=str, default=None)
    parser.add_argument("--n", type=int, default=1000)
    parser.add_argument("--cohort-minutes", type=float, default=10.0)
    parser.add_argument("--late-frac", type=float, default=0.10)
    parser.add_argument("--grace-s", type=float, default=300.0)
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--cohorts", type=str, default=DEFAULT_COHORTS)
    parser.add_argument("--max-mb", type=int, default=25)
    parser.add_argument("--students-prefix", type=str, default="student")
    parser.add_argument("--skip-finalize-frac", type=float, default=0.0)
    parser.add_argument("--out", type=str, required=True)

    args = parser.parse_args()

    rng = np.random.default_rng(args.seed)

    if args.mode == "placeholder":
        students = build_placeholder_schedule(args, rng)
    elif args.mode == "profiles":
        students = build_profiles_schedule(args, rng)
    else:
        students = build_bootstrap_schedule(args, rng)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Shard by cohort
    cohort_specs = parse_cohorts(args.cohorts)
    cohort_names = [name for name, _ in cohort_specs]
    by_cohort = {name: [] for name in cohort_names}
    counts_by_class = {"ON_TIME": 0, "MARGIN": 0, "LATE": 0}
    counts_by_cohort = {name: 0 for name in cohort_names}

    for s in students:
        c = s["cohort"]
        if c not in by_cohort:
            by_cohort[c] = []
            counts_by_cohort[c] = 0
        by_cohort[c].append(s)
        counts_by_cohort[c] += 1
        counts_by_class[s["plannedClass"]] += 1

    # Write shard-<cohort>.json
    for name, records in by_cohort.items():
        shard_path = out_dir / f"shard-{name}.json"
        with open(shard_path, "w", encoding="utf-8") as f:
            json.dump(records, f, indent=2)

    # Write shard-all.json
    all_path = out_dir / "shard-all.json"
    with open(all_path, "w", encoding="utf-8") as f:
        json.dump(students, f, indent=2)

    meta = {
        "mode": MODE_LABELS[args.mode],
        "label": MODE_LABELS[args.mode],
        "seed": args.seed,
        "n": args.n,
        "cohortMinutes": args.cohort_minutes,
        "lateFrac": args.late_frac,
        "graceSeconds": args.grace_s,
        "counts": counts_by_class,
        "classes": counts_by_class,
        "cohorts": counts_by_cohort,
    }
    if args.mode == "profiles":
        meta["profiles"] = PUBLIC_PROFILES

    meta_path = out_dir / "schedule-meta.json"
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)

    print(f"Generated schedule with {len(students)} students in {out_dir}:")
    print(f"  Classes: {counts_by_class}")
    print(f"  Cohorts: {counts_by_cohort}")


if __name__ == "__main__":
    main()
