# Cost per 1,000 submissions (phase 40)

Total cost: **₹3,121.37** usage cost before credits, project `deadlinedrop-nirmal`, 1-25 Nov 2026
(by service in [`billing-final.md`](billing-final.md): Compute Engine ₹1,565.54, Cloud SQL ₹1,172.06,
Cloud Run ₹246.00, Networking ₹52.28, Cloud Storage ₹48.01, Artifact Registry ₹37.48). Paid ₹0.

Submissions processed: **23,650** = sum of `size` in `meta.json` over the 45 runs in `results/runs/`
with `"counted": true` (16,650 in the fault-free sweeps, 7,000 in the failure, scale and cold-rush runs).
The 5 dry runs (`"counted": false`) are not counted. 4 of the 45 runs were later marked
invalid for an infrastructure reason (20261119T161908Z_A_100_r1, 20261121T183407Z_B_500_r3, 20261122T152557Z_A_500_r1_f1, 20261122T155620Z_A_500_r1_f1_rerun); their load still ran and cost
money, so they are counted.

Formula:

    cost per 1,000 submissions = total cost / submissions x 1,000
                               = ₹3,121.37 / 23,650 x 1,000
                               = ₹131.98

**₹131.98 per 1,000 submissions.**

What this number is: the whole project's cloud bill divided by every submission the measured runs sent.
The bill also covers 25 days of development, idle Cloud SQL, the k6 VMs (Compute Engine is half of it),
dry runs and smoke tests, so this is an upper bound on what the submissions themselves cost, not the
marginal cost of one more submission.

Reproduce the count:

    python -c "import json,glob;print(sum(json.load(open(f))['size'] for f in glob.glob('results/runs/*/meta.json') if json.load(open(f)).get('counted') is True))"

