# Final backups (phase 40, 2026-11-25)

Taken through cloud-sql-proxy (localhost only) before the Cloud SQL instance `dd-pg` was deleted.

- `dd_a.dump`, `dd_b.dump`, `dd_c.dump`: `pg_dump -Fc --compress=gzip:9` (PostgreSQL 17 client), one
  per arm database. Restore with `pg_restore -d <db> dd_c.dump`. Checksums in `SHA256SUMS`.
- `bucket-listing.txt`: `gcloud storage ls -r -l` of `gs://deadlinedrop-nirmal-dd-objects` (1,210
  objects, 8.92 GiB; the bytes themselves were load-test files and were not kept).

Telemetry: `telemetry_events` and `survey_responses` had 0 rows in all three databases (no pilot, D8),
so there was no aggregate telemetry to export. Users are the 1,002 seeded load-test accounts per
database (BCrypt hashes of a load-test seed password whose secret was destroyed in the same shutdown).
All four files total under 5 MB, so they are tracked in git (limit was 50 MB).
