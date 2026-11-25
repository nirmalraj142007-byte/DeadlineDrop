# Cloud inventory before shutdown (2026-11-25)

`gcloud ... describe --format=json` for every resource deleted in phase 40, taken before any delete,
project `deadlinedrop-nirmal`, region asia-south1:

| File | Resource |
| --- | --- |
| `run-dd-arm-{a,b,c}.json` | Cloud Run services (arms A, B, C) |
| `run-job-dd-seed-{a,b,c}.json` | Cloud Run seed jobs |
| `sql-dd-pg.json` | Cloud SQL instance `dd-pg` |
| `bucket.json` | Cloud Storage bucket `deadlinedrop-nirmal-dd-objects` |
| `scheduler-dd-{drain,reconcile}-c.json` | Cloud Scheduler jobs (OIDC, arm C) |

Redacted: the Cloud SQL server CA certificate body. Secret values never appear: the services read
`JWT_SECRET`, `DB_PASSWORD`, `TELEMETRY_SALT`, `RECEIPT_HMAC_KEY` and `SEED_PASSWORD` through Secret
Manager references (secret name + version only), and the scheduler jobs hold an OIDC audience and service
account, not a token. Also deleted (names only, no describe kept): Artifact Registry repo `dd`, secrets
`dd-db-password`, `dd-jwt-secret`, `dd-receipt-key`, `dd-seed-password`, `dd-telemetry-salt`, service
accounts `dd-run`, `dd-deployer`, `dd-scheduler`, Workload Identity pool `github`.
