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
