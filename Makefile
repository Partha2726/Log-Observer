# Log Observer: developer entry points (spec section 14.3).
# Every target runs from the repository root.

ENV_FILE := deploy/.env
COMPOSE  := docker compose -f deploy/docker-compose.yml --env-file $(ENV_FILE)

smoke-elastic:
	scripts/smoke-elastic.sh

# PARSE-003 end to end: generator -> Filebeat -> pass-through Logstash, counts against ground truth.
test-filebeat:
	scripts/test-filebeat.sh

.PHONY: smoke-elastic test-filebeat up down logs generate ingest-check demo reingest reset \
        test test-generator test-backend test-pipeline test-frontend \
        e2e eval bench ai-live-check

# First run on a clean clone: start from the placeholders.
$(ENV_FILE):
	cp deploy/.env.example $(ENV_FILE)
	@echo "Created $(ENV_FILE) from deploy/.env.example; edit the secrets before sharing the stack."

up: $(ENV_FILE)
	mkdir -p data/logs data/ground-truth
	$(COMPOSE) up -d --build --wait

down: $(ENV_FILE)
	$(COMPOSE) down

logs: $(ENV_FILE)
	$(COMPOSE) logs -f

generate: $(ENV_FILE)
	scripts/generate.sh

ingest-check: $(ENV_FILE)
	scripts/ingest-check.sh

demo: $(ENV_FILE)
	scripts/demo.sh

reingest: $(ENV_FILE)
	scripts/reingest.sh

reset: $(ENV_FILE)
	scripts/reset.sh

test: test-generator test-backend test-pipeline test-frontend

test-generator:
	@if [ -d generator ]; then cd generator && ./mvnw -B verify; else echo "test-generator: no generator yet (T03)"; fi

test-backend:
	cd backend && ./mvnw -B verify

test-pipeline:
	@if [ -x scripts/test-pipeline.sh ]; then scripts/test-pipeline.sh; else echo "test-pipeline: added in T08"; fi

test-frontend:
	cd frontend && npm ci && npm test && npm run build

# E2E-001…003 against the running stack (run make demo first; AI_PROVIDER=fake).
e2e:
	cd frontend && npx playwright install chromium && npx playwright test

eval:
	python3 -I evaluation/score.py

# T24: one live structured-output call and one tool call against each configured provider (primary, then
# AI_FALLBACK1_*, AI_FALLBACK2_*; one with an empty key is skipped), keys from deploy/.env. Sends generated data only.
ai-live-check: $(ENV_FILE)
	@set -a; . ./$(ENV_FILE); set +a; status=0; \
	for p in primary fallback1 fallback2; do \
	  if [ "$$p" = primary ]; then key="$$AI_API_KEY"; elif [ "$$p" = fallback1 ]; then key="$$AI_FALLBACK1_API_KEY"; else key="$$AI_FALLBACK2_API_KEY"; fi; \
	  if [ -z "$$key" ]; then echo "ai-live-check: $$p skipped (no key)"; continue; fi; \
	  if (cd backend && LIVE_PROVIDER=$$p ./mvnw -B -q test -Dgroups=live -DexcludedGroups=none -Dtest=LiveProviderCheckTest \
	        -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "LIVE|ERROR\]   |Api"); then :; fi; \
	  if (cd backend && grep -q 'errors="0" skipped="0" failures="0"' target/surefire-reports/TEST-com.ielop.platform.assistant.LiveProviderCheckTest.xml); \
	    then echo "ai-live-check: $$p PASS"; else echo "ai-live-check: $$p FAIL"; status=1; fi; \
	done; exit $$status

bench: $(ENV_FILE)
	scripts/bench.sh
