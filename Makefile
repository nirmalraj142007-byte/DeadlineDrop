ifeq ($(OS),Windows_NT)
SHELL := C:/Program Files/Git/bin/bash.exe
else
SHELL := /bin/bash
endif
.PHONY: verify up down logs terms seed k6-local charts e2e demo demo-reset demo-deadline demo-rush check

verify:
	bash tools/check-frozen.sh
	./mvnw verify

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs

terms:
	bash tools/check-terms.sh

seed:
	docker compose run --rm -e SPRING_PROFILES_ACTIVE=local,seed -e SEED_STUDENTS=$(N) -e SEED_PASSWORD -e DB_NAME=$(or $(DB),dd_c) app

k6-local:
	bash tools/k6/run-local.sh $(or $(ARM),c) $(or $(N),20)

# Phase 33: results/SUMMARY.md, results/headline.md and results/charts/*.png from every counted run.
charts:
	python tools/summary.py
	python tools/charts.py

# Phase 36: happy path then failure path (Postgres paused for 10 s) against the real compose stack,
# which is torn down with its volumes afterwards. Needs k6 and Docker.
e2e:
	bash tools/e2e/e2e.sh

# Phase 37: offline demo (two arm C replicas behind nginx on :8080, arm B on :8081, storage on :9000).
DEMO_COMPOSE := docker compose -f docker-compose.yml -f docker-compose.demo.yml

demo:
	$(DEMO_COMPOSE) up -d --build postgres app-c1 app-c2 nginx app-armb storage scheduler

demo-reset:
	bash tools/demo/demo-reset.sh

demo-deadline:
	bash tools/demo/demo-deadline.sh $(or $(SECONDS),120)

demo-rush:
	bash tools/demo/demo-rush.sh

check:
	bash tools/demo/check.sh
