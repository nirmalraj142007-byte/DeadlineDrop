# results/ — evidence layout

Every number in the README, the report and the resume must come from a file in this folder
(`tools/check-claims.sh`).

```
results/
  runs/               one folder per cloud run: <UTC stamp>_<ARM>_<size>_r<repeat>[_<fault>][_dryrun]
                        meta.json        arm, size, repeat, fault, counted, T, grace, cloud settings, skew, digest, git sha
                        preflight.json   parity, connection budget, clock skew, warm check (all numbers)
                        schedule/        shard-<cohort>.json, shard-all.json, schedule-meta.json
                        export.json      the teacher export for the run's assignment
                        vu-log-<cohort>.ndjson, summary-<cohort>.json, k6-<cohort>.out
                        confusion.json   tools/analyze.py: confusion cells, checks, latency, Server-Timing
  model/              fitted models and their inputs (pilot-derived)
  charts/             figures generated from runs/ and model/
  pilot/              anonymised pilot telemetry summaries (never names, emails or passwords)
  billing/            Cloud Billing exports and the cost summary
  ui/                 UI evidence (screenshots, accessibility results)
  sanity/             <cohort>.txt: shaped-link check from each k6 VM (rate ±10%, RTT ±10 ms)
  dryrun-1000/        vm-memory.txt: free -m on each VM every 5 s during the 1,000-student dry run
  coldstart.json      measured Cloud Run cold-start times (tools/coldstart.sh)
  probe-buffering.md  Cloud Run request-buffering probe (arm A rationale)
  smoke-bucket.log    Cloud Storage smoke-test log
  SUMMARY.md          the headline numbers, each pointing at the run folders it came from
```

## Rules

1. **Counted results are never edited by hand.** A run is counted when its `meta.json` has
   `"counted": true`. Fix a problem by adding a new run, never by changing a file.
2. **Counted runs are never re-run to get a better number.** A run may be repeated only when its
   `meta.json` marks it invalid for an infrastructure reason; keep both folders.
3. **Dry runs are labelled `"counted": false`** (`tools/run.sh ... --dryrun`, folder suffix `_dryrun`).
4. After tag `evidence-frozen` (day 17), `results/runs/` is a measured path: `tools/check-frozen.sh`
   rejects any change to it.
5. `results/local/` and `results/flow-live/` hold earlier local and smoke output; they are not
   counted evidence.
