# DeadlineDrop

DeadlineDrop: bandwidth-fair deadline submissions

## Quick start

```bash
cp .env.example .env
# Edit .env and set JWT_SECRET (generate with: openssl rand -base64 48)
docker compose up
```

## Run checks

```bash
./mvnw verify          # unit + integration tests
make up                # Docker Compose (postgres + app)
make down              # stop containers
```
