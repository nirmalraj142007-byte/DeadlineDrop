# Pre-registered predictions

Committed on day 1 (Fri 6 Nov 2026) before any code that could produce a result. Never edited. Later predictions are appended as new sections in new commits.

## Definitions

- **cohort**: simulated students who click in the final N minutes before the deadline (N = 10 unless the pilot's click curve says otherwise; N is fixed in an appended section before the first sweep).
- **Truly on time**: clicked at least 5 s before the deadline by the k6 VM's NTP-synced clock.
- **Misclassified**: truly on time but recorded LATE or missing.
- **Truly late**: clicked after the deadline; at least 10% of every run.
- Clicks in [T-5 s, T) are reported separately.
- **Arms**:
  - A = tutorial multipart through the app, stamped at upload completion;
  - B = direct signed-URL upload, stamped at finalize;
  - C = DeadlineDrop, hash commitment stamped on arrival.

## Predictions

1. Arm A, 1,000-student cohort, mixed bandwidth: 15-35% misclassified.
2. Arm A, fast clients only: under 2% (the baseline survives fast clients).
3. Arm B: close to the analytic model once it exists; load adds under 3 points.
4. Arm C: 0 on-time-to-late, 0 late-to-on-time, 0 lost, p95 commit latency under 500 ms; cold-rush p95 under 5 s.
5. First bottleneck: IAM URL signing or the connection pool.
6. Arm A at 10 instances: rate moves by under 3 points.
7. F3 database restart without a signed receipt: more than 0 students misjudged.

## Would embarrass me

- arm B under 3% in the cohort;
- any lost submission in F1;
- any arm C late-to-on-time.

## Fallback rule (fixed now)

If arm B's cohort rate is under 5%, the headline leads with the absolute count per 1,000-student course and the B-versus-C ablation; the cohort is never widened to raise the number.


## Addendum 1 (day 6, 11 Nov 2026, buffering probe)

The empirical buffering probe (`results/probe-buffering.md`) measured `request_arrived` at 164.554 s after `clientStart` on a 165.381 s upload, proving that Cloud Run's front end buffers the complete multipart request body before forwarding the request to the container.

Because Cloud Run buffers rather than streams:
- Slow uploads do not occupy container concurrency slots or Tomcat threads during transfer; slots are held by the front end while bytes trickle in.
- The container sees arrivals not as long-lived open connections, but as concentrated bursts of complete bodies when uploads finish.
- Arm A's failure mode under load is governed by memory pressure from multipart spooling per request and front-end queueing when bursts of completed bodies arrive simultaneously, rather than container thread exhaustion mid-transfer.

## Addendum 2 (day 14, 19 Nov 2026, before the first counted sweep)

Written before any counted run exists (`results/runs/` holds dry runs only). Model:
`tools/model/model.py`; every number below is in `results/model/model-output.json` and
`results/model/sensitivity.csv` (seed 20261119, 10,000 simulated runs).

**N rule (fixed before the numbers were computed).** N = the smallest whole number of minutes
before the deadline that contains at least one third of all pilot submissions, clamped to
5-15; with fewer than 30 submissions, N = 10. There is no pilot (D8: 0 submissions), so
**N = 10**.

**Pilot.** None (D8). Network inputs are published measurements, Ookla open data for India,
2026 Q3 (`docs/network-profiles.md`): slow 7 Mbps / 40 ms, medium 20 Mbps / 25 ms,
fast 54 Mbps / 10 ms. The k6 VMs are shaped to the same values.

**Rules.** A click c seconds before the deadline, a file of S bytes, upload rate U, round
trip r, hash time h: A is late if c < 8S/U + r; B is late if c < 8S/U + 3r; C is late if
c < h + r. Rates are over truly on-time students (c >= 5 s); [0, 5) s is reported apart.

**Stated assumptions (no public source exists for these).**
- Cohort weights slow 0.3, medium 0.4, fast 0.3: the mix the dry runs used.
- File sizes 1 / 2 / 5 / 10 / 15 / 25 MiB at 10 / 20 / 30 / 20 / 15 / 5%: the schedule's buckets.
- Hash time = size / 80 MiB/s: browser SHA-256 speed, as the k6 script sleeps it.
- Click curve: convex, 40% of the cohort in the final 60 s of the 10-minute window.
- One third of a course submits in the final 10 minutes (used only for the per-course count).
- The model has no load: no TCP slow start, retries, queueing or cold starts.
- Cohort speeds are percentiles of Ookla tile averages, which understate the spread between individual users; the 0.5x row approximates the slower individual connections that the averages hide.
The 0.5x-2x speed range also covers stretching the click curve by the inverse factor
(D8's click-curve test), because RTT is small next to transfer time.

**Predictions (1,000-student cohort run: 900 on the curve, 100 late).**
- Arm A: 1.30% misclassified at 1x (0.32% at 2x to 3.83% at 0.5x).
- Arm B: 1.32% at 1x (0.33% at 2x to 3.85% at 0.5x); one run's sampling spread at 1x is
  0.58%-2.10%. Per 1,000-student course: 4.2 misjudged on-time students at 1x
  (1.0 at 2x, 12.3 at 0.5x).
- Arm C: 0 at every speed (worst case hash + RTT = 0.35 s, under the 5 s line).
- Measured B should land within 3 points of the model. Measured A minus the model is the
  load-induced error.

**Effect on the day-1 section.** The model puts B under the 3% in "Would embarrass me" and
under the 5% in the fallback rule. The fallback rule therefore applies as written: the
headline leads with the count per 1,000-student course and the B-versus-C ablation, and the
cohort is not widened. Day-1 prediction 1 (A 15-35%) was made before the public profiles
existed. Any measured A above about 1.3% comes from load, not bandwidth.
