# Demo setup (works with no internet)

The demo runs entirely on this laptop: two arm C replicas behind nginx on `:8080`, arm B on
`:8081`, the local storage server on `:9000`, Postgres, and a scheduler container. Nothing in
it calls Google Cloud, so it still works after the cloud project is deleted.

Do the **one-time online steps** once, while you still have internet:

1. `docker pull nginx:1.27-alpine` (the only image the base stack does not already use).
2. `make demo` once, so the app image is built and Maven's dependencies are cached in the image.
3. Install k6 (the pinned version is in `tools/README.md`) and confirm `k6 version` works.

## Sequence before an audience

Run these in order, from the repository root, in Git Bash or `make`'s shell.

1. **Start the stack**: `make demo`. It starts postgres, `app-c1`, `app-c2`, nginx, `app-armb`,
   storage and the scheduler. The plain `app` service is not started in demo mode.
2. **Reset the data**: `make demo-reset`. It drops and recreates `dd_b` and `dd_c`, lets Flyway
   rebuild the schema, seeds 200 students (`student0001` to `student0200`), the teacher and an
   admin, creates course `DEMO101` and assignment **Lab 4** (grace 15 min, 25 MB, PDF) in both,
   and writes the two Lab 4 ids to `tools/demo/.lab4` (git-ignored). The password for every
   seeded account is `SEED_PASSWORD` from your `.env`.
3. **Preflight**: `bash tools/demo/demo-preflight.sh`. Every line must say `ok:`. It also creates
   the 10 MB test file `tools/demo/lab4.pdf` if it is missing. Fix anything that says `FAIL:`
   and run it again. On success it prints the throttle reminder.
4. **Two Chrome profiles**, one window each, side by side:
   - Profile "Arm B": open `http://localhost:8081`, log in as `student0001@dd.local`.
   - Profile "Arm C": open `http://localhost:8080`, log in as `student0001@dd.local`.
   Use two separate profiles so the two logins do not share a session.
5. **Teacher dashboard tab**: in a third window (or a third profile) open `http://localhost:8080`
   and log in as `teacher@dd.local`; open the Lab 4 dashboard. The `:8080` address is nginx, so
   the dashboard keeps working if one replica is killed.
6. **Throttle profile**: in both student windows open DevTools, Network tab, throttling menu,
   choose **Hostel 1 Mbps** (custom profile: download 1000 kbit/s, upload 1000 kbit/s, latency
   80 ms). Create it once under *Add...* and it stays in the list. Keep DevTools open: Chrome
   only throttles while DevTools is open.
7. **Clips folder**: create `C:\dev\deadlinedrop\demo-clips\` (outside git: add nothing from it)
   and put your screen recordings there as fallbacks, one per scene: arm B late, arm C on time,
   the crash mid-rush. If the live demo fails, play the clip and carry on.

## Running a scene

- Set Lab 4's deadline: `make demo-deadline SECONDS=45` (the script prints the deadline in IST).
  Both databases are updated, so both windows count down to the same moment.
- Pick `tools/demo/lab4.pdf` in both student windows and press submit at the same moment. At
  1 Mbps the 10 MB file takes about 80 seconds, so a click just before the deadline reaches
  storage after it: arm B stamps at finalize and calls it late, arm C stamped at commit.
- Class-wide rush: `make demo-deadline SECONDS=120`, then `make demo-rush`. To show a crash,
  run `docker kill <container>` for `app-c1` about 60 seconds in (find the name with
  `docker ps`). nginx moves traffic to `app-c2`; clients retry with the same Idempotency-Key.
- Afterwards: `make check`. It exports Lab 4 from `:8080`, compares it with the rush log, and
  prints `lost N · duplicates N (check)` plus how many students who never finalized still ended
  `UPLOADED` or `VERIFIED`. Lost and duplicates must both be 0. The reconciler only settles a commit that is more than
  2 minutes old, so run `make check` at least 2 minutes after the rush, or the students who
  skipped finalize still show as `COMMITTED` (stamped, not lost).

## Between rehearsals

`make demo-reset` brings everything back to a clean Lab 4. `make down` stops the base stack;
to stop the demo stack use
`docker compose -f docker-compose.yml -f docker-compose.demo.yml down`.

## Limits

- `demo-deadline.sh` writes the deadline straight into Postgres with psql. That is demo tooling,
  not product behaviour: no fairness decision uses SQL `now()`.
- nginx resolves the two replicas when it starts. If you recreate a replica container, run
  `docker compose -f docker-compose.yml -f docker-compose.demo.yml restart nginx`.
