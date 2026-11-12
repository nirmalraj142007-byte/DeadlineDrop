# DeadlineDrop Load Generator Tools

This directory contains the load generation, schedule synthesis, and benchmarking scripts for DeadlineDrop.
The load generator is written once and used across all local and cloud evidence runs.

## Pinned Versions

- **k6**: `v2.3.0` (`k6.exe v2.3.0 (commit/e088784614, go1.26.8, windows/amd64)`)
- **Python**: 3.12+ (standard library + `numpy`)

## What PLACEHOLDER Means

The schedule generator runs in two modes:
- **`placeholder`**: Generates synthetic student cohorts and click offsets based on pre-registered assumptions:
  - Bandwidth: Log-normal distribution with medians 1 Mbps (slow), 4 Mbps (medium), and 20 Mbps (fast).
  - Sizes: Buckets {1, 2, 5, 10, 15, 25} MB with weights 10, 20, 30, 20, 15, 5 capped at `--max-mb`.
  - Hashing: $T_{\text{hash}} = \text{size} / 80\text{ MB/s}$ simulating in-browser Web Crypto SHA-256 calculation.
  - Arrival curve: Convex last-minutes rush curve where 40% of the cohort clicks in the final 60 seconds.
  - Every output shard and metadata file is explicitly marked `PLACEHOLDER` so synthetic runs are never confused with empirical measurements.
- **`bootstrap`**: Resamples `(size, speed, rtt, hashMs)` tuples and click offsets directly from the pilot CSV collected during live runs.

## Components

### 1. `tools/gen_schedule.py`
Generates student schedules partitioned into shards:
```bash
python tools/gen_schedule.py \
  --mode placeholder \
  --n 1000 \
  --cohort-minutes 10 \
  --late-frac 0.10 \
  --grace-s 300 \
  --seed 1 \
  --cohorts "slow:0.3,medium:0.4,fast:0.3" \
  --max-mb 25 \
  --out results/schedule
```
Outputs:
- `shard-<cohort>.json`: Array of student records per cohort (`slow`, `medium`, `fast`).
- `shard-all.json`: Full combined student schedule.
- `schedule-meta.json`: Metadata with seed, parameters, counts per class (`ON_TIME`, `MARGIN`, `LATE`), and counts per cohort.

### 2. `tools/k6/files/make-files.sh`
Generates binary test payload files for each size bucket in `tools/k6/files/out/`:
```bash
MAX_MB=25 bash tools/k6/files/make-files.sh
```

### 3. `tools/k6/lib/flows.js`
Implements the upload flows for all three arms:
- **`armC`** (DeadlineDrop): Client computes SHA-256 hash commitment, stamps upon commit arrival, PUTs bytes to signed URL, and calls finalize.
- **`armB`** (`direct-finalize`): Direct upload URL request, PUT bytes to storage, and stamps upon finalize.
- **`armA`** (`naive`): Multipart file upload streaming directly through the application server, stamped upon complete byte receipt.
All arms follow the browser retry policy:
- Full jitter exponential backoff (base 500 ms, cap 8 s).
- Retries on status 0, 429, and 5xx; never on 4xx.
- Preserves the same `Idempotency-Key` across retries.
- Gives up when $T + \text{grace}$ is reached.
- Logs structured JSON records per student for analysis.

### 4. `tools/k6/rush.js`
Executes the rush scenario using k6:
```bash
k6 run \
  --console-output results/local/vu-log.ndjson \
  -e ARM=c \
  -e BASE_URL=http://localhost:8080 \
  -e SHARD=results/schedule/shard-all.json \
  -e T_EPOCH_MS=1794485215000 \
  -e GRACE_S=300 \
  -e PASSWORD=secret \
  -e FILES_DIR=tools/k6/files/out \
  tools/k6/rush.js
```

### 5. `tools/k6/sanity.js`
Sanity check uploading a single 20 MB file through Arm C:
```bash
k6 run -e BASE_URL=http://localhost:8080 tools/k6/sanity.js
```
Prints commit round-trip time (RTT), PUT transfer duration, and calculated upload throughput in Mbps.

### 6. Local End-to-End Run (`make k6-local`)
Runs the full local pipeline against Docker Compose:
```bash
make k6-local ARM=c N=20
make k6-local ARM=b N=20
make k6-local ARM=a N=20
```
Performs:
1. File payload generation (`MAX_MB=2`).
2. Schedule synthesis (`cohort-minutes 1`, `N=20`).
3. Execution of `rush.js` with output logged to `results/local/<timestamp>-<arm>/vu-log.ndjson`.
4. Waits for $T + \text{grace} + 2\text{ min}$ to allow the transactional job queue to drain and reconcile.
5. Fetches `/api/assignments/{id}/export` as JSON and prints counts by status.
