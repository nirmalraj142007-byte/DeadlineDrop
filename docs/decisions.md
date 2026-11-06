# Architectural and Project Decisions

## D1: Deployment Region (2026-11-06)
Region `asia-south1` (Mumbai) is selected for all cloud infrastructure: Cloud Run, Cloud SQL, the Google Cloud Storage bucket, and k6 load-testing VMs.

## D2: Scope Cuts and Replacements (2026-11-06)
Features cut on day 1 to protect the core deadline guarantee and evaluation timeline, along with their replacements:
- **Cross-student duplicate detection**: Moved to stretch goal.
- **CSV bulk import**: Replaced by automated database seed script.
- **Downloadable receipt file**: Replaced by web-based receipt page (retained).
- **2,000-student sweep point**: Cut to keep within free-tier compute and connection budgets.
- **OpenAPI example payloads**: Replaced by standard generated API documentation (retained).
- **Teacher CSV export**: Replaced by per-file submission inspection and download-all archive endpoints.

## D3: Working Assumptions (2026-11-06)
- Three resume impact bullets drafted upfront to guide benchmark targets.
- Export endpoint is API-only.
- Predictions and accuracy metrics are defined strictly in cohort terms.
- Deadline countdown race simulation begins at 0:30 in the demo.
- Final project review scheduled after 27 Nov 2026, so the live demo runs locally.
- 5-minute experiment grace period configured for overnight parameter sweeps.
- Submissions committed after the official deadline timestamp are accepted and recorded as LATE.
- JWT access tokens have a 12-hour validity window.

## D4: Pilot Request and Gate (2026-11-06)
Pilot request sent today (Fri 6 Nov 2026). Decision gate is set for day 5 (Tue 10 Nov 2026): written confirmation from a course instructor/club lead, or fallback to a scheduled mock session with 30+ participants on day 13 or 14.

## D5: Claude Code Usage Windows (2026-11-06)
To maximize throughput and review quality within rate windows: generate implementations in the first window of each day; inspect, read code, and run tests in the interval gaps.

## D6: Credit Balance on Day 1 (2026-11-06)
Credit balance: 
The project budget cap is approximately US$40. Because billing account `011506-D31873-0CFA73` operates in `INR` (Indian Rupee), the budget alert `dd-40` is expressed in INR, and the exact amount to use is `3500INR`.
