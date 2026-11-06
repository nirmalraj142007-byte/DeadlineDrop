.PHONY: verify up down logs terms

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
