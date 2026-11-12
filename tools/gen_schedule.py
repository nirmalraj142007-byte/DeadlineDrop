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

SIZE_BUCKETS_MB = [1, 2, 5, 10, 15, 25]
SIZE_BUCKET_WEIGHTS = [10, 20, 30, 20, 15, 5]
HASH_RATE_MB_PER_S = 80.0


def parse_cohorts(cohorts_str: str) -> list[tuple[str, float]]:
    parts = cohorts_str.split(",")
    cohorts = []
    for part in parts:
        name, weight = part.split(":")
        cohorts.append((name.strip(), float(weight.strip())))
    total = sum(w for _, w in cohorts)
    return [(name, w / total) for name, w in cohorts]


def build_placeholder_schedule(args, rng: np.random.Generator):
    cohort_specs = parse_cohorts(args.cohorts)
    cohort_names = [name for name, _ in cohort_specs]
    cohort_probs = [prob for _, prob in cohort_specs]

    # Filter sizes by max_mb
    available_sizes = [s for s in SIZE_BUCKETS_MB if s <= args.max_mb]
    available_weights = [w for s, w in zip(SIZE_BUCKETS_MB, SIZE_BUCKET_WEIGHTS) if s <= args.max_mb]
    if not available_sizes:
        available_sizes = [args.max_mb]
        available_weights = [1.0]
    total_w = sum(available_weights)
    size_probs = [w / total_w for w in available_weights]

    n = args.n
    late_count = max(int(math.ceil(n * args.late_frac)), 1) if args.late_frac > 0 else 0
    cohort_count = n - late_count

    # Click offsets for cohort students: convex last-minutes curve (40% in final 60 s)
    w_ms = args.cohort_minutes * 60.0 * 1000.0
    if w_ms > 60000.0:
        beta = math.log(0.60) / math.log(1.0 - 60000.0 / w_ms)
    else:
        beta = 2.0

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

        median_speed = SPEED_MEDIANS_MBPS.get(cohort, 4.0)
        speed_mbps = float(np.exp(rng.normal(math.log(median_speed), 0.4)))

        skip_finalize = bool(rng.uniform(0.0, 1.0) < args.skip_finalize_frac)

        students.append({
            "mode": "PLACEHOLDER",
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
    parser.add_argument("--mode", choices=["placeholder", "bootstrap"], default="placeholder")
    parser.add_argument("--pilot-csv", type=str, default=None)
    parser.add_argument("--n", type=int, default=1000)
    parser.add_argument("--cohort-minutes", type=float, default=10.0)
    parser.add_argument("--late-frac", type=float, default=0.10)
    parser.add_argument("--grace-s", type=float, default=300.0)
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--cohorts", type=str, default="slow:0.3,medium:0.4,fast:0.3")
    parser.add_argument("--max-mb", type=int, default=25)
    parser.add_argument("--students-prefix", type=str, default="student")
    parser.add_argument("--skip-finalize-frac", type=float, default=0.0)
    parser.add_argument("--out", type=str, required=True)

    args = parser.parse_args()

    rng = np.random.default_rng(args.seed)

    if args.mode == "placeholder":
        students = build_placeholder_schedule(args, rng)
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
        "mode": "PLACEHOLDER" if args.mode == "placeholder" else "BOOTSTRAP",
        "label": "PLACEHOLDER" if args.mode == "placeholder" else "BOOTSTRAP",
        "seed": args.seed,
        "n": args.n,
        "cohortMinutes": args.cohort_minutes,
        "lateFrac": args.late_frac,
        "graceSeconds": args.grace_s,
        "counts": counts_by_class,
        "classes": counts_by_class,
        "cohorts": counts_by_cohort,
    }

    meta_path = out_dir / "schedule-meta.json"
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)

    print(f"Generated schedule with {len(students)} students in {out_dir}:")
    print(f"  Classes: {counts_by_class}")
    print(f"  Cohorts: {counts_by_cohort}")


if __name__ == "__main__":
    main()
