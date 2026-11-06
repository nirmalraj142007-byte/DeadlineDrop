.PHONY: verify up down logs terms seed

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
