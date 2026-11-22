SHELL := C:/Program Files/Git/bin/bash.exe
.PHONY: verify up down logs terms seed k6-local charts

verify:
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
