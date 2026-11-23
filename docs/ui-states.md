# UI state walkthrough — phase 35

Walked on 24 Nov 2026 against the local stack (`docker compose up`, arm C, `http://localhost:8080`) in a
real Chrome driven over the DevTools Protocol (puppeteer-core). Every session signed in through the real
login form as `student0001`, `teacher` or `admin` with the local seed password from `.env` (never printed).

How each state was produced:

- **loading** — the one request that feeds the screen was held at the browser (CDP request interception)
  and the state was read while it was pending. `/api/time` was never held, because `api.js` awaits it
  at module load (see finding 1).
- **error** — the request was aborted with connection refused, then the Retry button was used where one exists.
  Separately the **app container was really stopped** (`docker compose stop app`) with S7 open (partial, below)
  and with a reload (Chrome's own connection-refused page, because the static files come from the app too),
  then started again.
- **partial** — the first load succeeded, then a later request failed (S7, S4), or one of two parallel
  requests failed (S5, S9).
- **empty / success** — real data. Throwaway local accounts `empty.student@dd.local` and
  `empty.teacher@dd.local` (same hash as the seed users) were inserted into `dd_c` for the empty cases.
  The success data was created through the UI: course `UI85748` (S5), assignments `UI walk Lab` and
  `UI walk Empty roster` (S6), a real PDF submitted by `student0001` through S3.

Error cells that quote “Failed to fetch” were walked before finding 2 below was fixed; that text is now “Can't reach DeadlineDrop. Check your connection; we'll retry.”

`seen` means I read the state in the browser and a screenshot was taken. `N/A` means the state cannot exist.

| Screen | No data / empty | Loading | Error | Partial | Success |
| --- | --- | --- | --- | --- | --- |
| S1 Login | seen — empty submit: the browser's native required-field message, no request sent | seen — login held: button disabled, page unchanged | seen — wrong password shows “Wrong email or password”; aborted request shows “Can't reach DeadlineDrop…” (“Failed to fetch” when walked, before the fix), button re-enabled | N/A — login is one request | seen — student lands on `/student/assignments.html`, teacher on `/teacher/courses.html`, admin on `/admin/console.html` |
| S2 Assignments | seen — `empty.student` sees “No assignments yet” | seen — “Loading assignments…” | seen — “Could not load assignments / Failed to fetch / Retry”; Retry recovered to the 13-row table | N/A — the list is reloaded only by Retry, which exists only after a failure, and the cache is filled only on success, so a refresh failing after a render cannot be reached | seen — table with the new `UI walk Lab` row (countdown, “Not submitted”, Submit) |
| S3 Submit | seen — no `assignment` id: “No assignment specified” | seen — “Loading assignment…”, form card hidden | seen — “Could not load assignment / Failed to fetch / Retry” | N/A — config and assignment load in one `Promise.all`; the invalid-file message (“File type "text/plain" not allowed”, Submit disabled) is a validation state, seen | seen — PDF chosen, progress “Locking your submission time…” → “Time locked ✓” → “Confirming submission bytes…”, then “Submission Received, ON TIME ✓” with View Receipt |
| S4 Receipt | seen — no `id`: “No submission specified” | seen — “Loading receipt…” | seen — “Could not load receipt / Failed to fetch / Retry” | seen — on a pending receipt with the 5 s poll failing, the last receipt stays and a warning says it could not refresh (added this phase; before it the failure was silent) | seen — ON TIME, timeline (time locked, file arrived, verified), SHA-256 with Copy, survey card |
| S5 Courses | seen — `empty.teacher` sees “No courses yet” | seen — “Loading courses…” with no rows | seen — “Could not load courses / Retry” | seen — `/api/courses` ok, per-course assignment lists failed: 6 course rows plus “Some assignment details could not load / Retry” | seen — created `UI85748` through the form, it appears in the list; a repeat code shows “That course code is taken” |
| S6 Assignment form | seen — no course in the URL: “Choose a course before creating an assignment.”; blank title and deadline show the field errors “must not be blank” / “must not be null” | seen — edit load shows “Loading assignment…” with Save disabled; Save shows “Saving…” and is disabled (both added this phase; before, the form looked idle) | seen — failed save: banner “Can't reach DeadlineDrop…” (“Failed to fetch” when walked, before the fix), button back to “Save changes”; failed edit load: “Could not load this assignment.”, Save stays disabled | N/A — form setup is a single request | seen — created `UI walk Lab` in DEMO101 and `UI walk Empty roster` in the new course, redirected to the list, rows present; “Students see” echoes the IST deadline |
| S7 Dashboard | seen — assignment with no enrolled students: all tiles 0, “No students are enrolled in this course yet.”, Download all disabled; no `assignment` id: “No assignment specified” | seen — “Loading dashboard…”, dashboard hidden | seen — “Could not load the dashboard / Failed to fetch / Retry”; Retry recovered | seen — after the first render the poll failed (once by interception, once by really stopping the app): tiles and rows stay, badge “LAST UPDATED 9 S AGO”; badge cleared when the poll recovered | seen — `UI walk Lab`: 1 verified, 999 not submitted, `student0001` row VERIFIED with timestamps and Download |
| S8 Submission detail | seen — no `id`: “No submission specified” | seen — “Loading submission…” | seen — “Could not load submission / Failed to fetch / Retry” | N/A — detail is one request | seen — opened from the dashboard row: ON TIME, VERIFIED, timeline, SHA-256, size and type, Download, event table, “Back to dashboard” |
| S9 Admin console | seen — a student session or no session: “Administrator access required” | seen — “Loading deployment details…”, values show “Loading…” | seen — “Could not load the admin console / Failed to fetch” | seen — config and time ok, chaos-status request failed: arm shown, chaos line shows the error, Halt button stays hidden | seen — admin: “C — DeadlineDrop: time locked when you commit”, server time IST, skew +2 ms, “Chaos disabled (CHAOS_ENABLED is off).”, Halt hidden |

## Findings from the walkthrough

1. **Blank page while `/api/time` is slow — fixed.** `api.js` awaited three sequential `/api/time` samples at
   load, so every screen stayed blank. The sync now runs in the background (retrying every 5 s until a sample
   arrives); pages render their loading state at once, and countdowns show “syncing clock…” until the offset is known.
   Checked in Chrome with `/api/time` held: S2 rendered its 13 rows with “syncing clock…”, then the real countdown.
   The server stamp (`committed_at`) does not use this offset.
2. **Raw browser text on network failure — fixed.** `apiFetch` now turns the fetch TypeError into an `ApiError`
   (status 0, so retry logic treats it as before) reading “Can't reach DeadlineDrop. Check your connection; we'll retry.”
   Seen on S2 and S1.
3. **Fixed this phase** (static files only): S4 warns when a poll fails; S6 shows loading on edit and save and
   re-enables the button on failure (a double-click during a slow save could have created two assignments).
4. The raw Chrome connection-refused page is what a real outage shows for a reload, because the pages are served by the
   app. The in-page states above cover the case where the app goes away after the page has loaded.

## Accessibility

Lighthouse 13.6.0, accessibility category, desktop, run with its user-flow API in the logged-in tab (navigation
mode with `disableStorageReset`, so `sessionStorage` and the bearer token survive each navigation). Reports are in
`results/ui/`.

| Screen | Page | Score | Report |
| --- | --- | --- | --- |
| S3 Submit | `/student/submit.html?assignment=<UI walk Lab>` | 100 | `results/ui/lighthouse-S3.json` |
| S4 Receipt | `/student/receipt.html?id=<submission>` | 100 | `results/ui/lighthouse-S4.json` |
| S7 Dashboard | `/teacher/dashboard.html?assignment=<UI walk Lab>` | 100 | `results/ui/lighthouse-S7.json` |

Each run ended on the requested page (not the login redirect), and the label and colour-contrast audits were
applicable on S3 and S4 (colour contrast also on S7), so the pages were rendered with content when scored.

Source review notes: form fields in S1, S5, S6 and S3 have associated labels; progress and status messages use
live regions; S4's timeline is an ordered list; shared CSS has a visible `:focus-visible` outline and reduced-motion
handling. The S9 console has live status regions, and its halt action is hidden unless the status request
succeeds and needs `confirm()`.
