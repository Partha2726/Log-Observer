#!/usr/bin/env bash
# T06 smoke test: every long-running service of the stack is healthy, setup exited 0,
# and the least-privilege users behave (backend_reader cannot write; logstash_writer can).
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=deploy/.env
set -a; source "$ENV_FILE"; set +a
COMPOSE=(docker compose -f deploy/docker-compose.yml --env-file "$ENV_FILE")
ES=http://localhost:9200
PATTERN_INDEX="logs-flow-smoke-$(date +%s)"
fail=0

check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }

for svc in elasticsearch logstash filebeat postgres backend frontend; do
  state=$("${COMPOSE[@]}" ps --format '{{.Health}}' "$svc" 2>/dev/null || true)
  check "$svc healthy" "[[ '$state' == 'healthy' ]]"
done
setup_exit=$(docker inspect -f '{{.State.ExitCode}}' log-observer-setup 2>/dev/null || echo missing)
check "setup exited 0" "[[ '$setup_exit' == '0' ]]"

code=$(curl -s -o /dev/null -w '%{http_code}' -u "backend_reader:${ES_PASSWORD}" -X POST "$ES/$PATTERN_INDEX/_doc" \
  -H 'Content-Type: application/json' -d '{"message":"smoke"}')
check "backend_reader cannot write (HTTP $code)" "[[ '$code' == '403' ]]"

code=$(curl -s -o /dev/null -w '%{http_code}' -u "backend_reader:${ES_PASSWORD}" "$ES/logs-flow-*/_count")
check "backend_reader can read logs-flow-* (HTTP $code)" "[[ '$code' == '200' ]]"

code=$(curl -s -o /dev/null -w '%{http_code}' -u "logstash_writer:${LOGSTASH_WRITER_PASSWORD}" -X POST "$ES/$PATTERN_INDEX/_create/smoke-1" \
  -H 'Content-Type: application/json' -d '{"message":"smoke"}')
check "logstash_writer can create documents (HTTP $code)" "[[ '$code' == '201' ]]"

code=$(curl -s -o /dev/null -w '%{http_code}' -u "logstash_writer:${LOGSTASH_WRITER_PASSWORD}" "$ES/_security/user")
check "logstash_writer cannot read security users (HTTP $code)" "[[ '$code' == '403' ]]"

# A plain index once the logs-flow template is installed; a data stream if it is not.
curl -s -o /dev/null -u "elastic:${ELASTIC_PASSWORD}" -X DELETE "$ES/$PATTERN_INDEX?ignore_unavailable=true" || true
curl -s -o /dev/null -u "elastic:${ELASTIC_PASSWORD}" -X DELETE "$ES/_data_stream/$PATTERN_INDEX" || true
exit $fail
