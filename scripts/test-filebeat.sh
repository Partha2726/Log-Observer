#!/usr/bin/env bash
# T07 / PARSE-003 (end to end): generate a small run, ship it with the real Filebeat config to a
# pass-through Logstash, and check that the event count equals the ground truth (lines.jsonl)
# and that every format D event keeps its continuation lines (stack traces, SQL, XML).
# Uses its own Docker network and containers, so it can run next to the main stack.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
START="${GEN_START:-}"   # optional caller overrides; .env must not replace them
SEED="${GEN_SEED_OVERRIDE:-42}"
OTEL="${GEN_OTEL_OVERRIDE:-false}"
set -a; source deploy/.env.example; [[ -f deploy/.env ]] && source deploy/.env; set +a

FLOWS="${FLOWS:-10}"
WORK="$ROOT/data/test/filebeat"
NET=log-observer-fbtest
LS=log-observer-fbtest-logstash
FB=log-observer-fbtest-filebeat

cleanup() { docker rm -f "$FB" "$LS" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

rm -rf "$WORK" && mkdir -p "$WORK/out"
if [[ ! -f generator/target/generator.jar ]]; then (cd generator && ./mvnw -B -q package -DskipTests); fi
java -jar generator/target/generator.jar --flows "$FLOWS" --days 1 --seed "$SEED" --otel "$OTEL" --legacy-timezone Asia/Kolkata ${START:+--start "$START"} \
  --out "$WORK/logs" --truth "$WORK/truth" --flow-definition config/flows/purchase-request.yaml
chmod -R a+rwx "$WORK/out"

docker network create "$NET" >/dev/null
PIPELINE_CONF="${PIPELINE_CONF:-$ROOT/deploy/logstash/test/passthrough.conf}"
docker run -d --name "$LS" --network "$NET" -e LS_JAVA_OPTS="-Xms256m -Xmx256m" -e XPACK_MONITORING_ENABLED=false \
  -e COUNTERPARTY_HASH_KEY="${COUNTERPARTY_HASH_KEY:-test-counterparty-key}" -e LOG_TIMEZONE=Asia/Kolkata \
  -v "$PIPELINE_CONF:/usr/share/logstash/pipeline/logstash.conf:ro" \
  -v "$ROOT/deploy/logstash/patterns:/usr/share/logstash/patterns:ro" \
  -v "$ROOT/config/flows:/usr/share/logstash/flows:ro" \
  -v "$WORK/out:/out" "docker.elastic.co/logstash/logstash:${ELASTIC_VERSION}" >/dev/null
for _ in $(seq 1 90); do
  docker exec "$LS" curl -fs localhost:9600/_node/stats >/dev/null 2>&1 && break; sleep 2
done
docker run -d --name "$FB" --network "$NET" --user root -e LOGSTASH_HOSTS="$LS:5044" \
  -v "$ROOT/deploy/filebeat/filebeat.yml:/usr/share/filebeat/filebeat.yml:ro" \
  -v "$WORK/logs:/logs:ro" "docker.elastic.co/beats/filebeat:${ELASTIC_VERSION}" \
  filebeat -e --strict.perms=false >/dev/null

expected=$(wc -l < "$WORK/truth/lines.jsonl" | tr -d ' ')
last=-1; stable=0; count=0
for _ in $(seq 1 120); do
  count=$( (cat "$WORK"/out/*.jsonl 2>/dev/null || true) | wc -l | tr -d ' ')
  if [[ "$count" == "$expected" ]]; then break; fi
  if [[ "$count" == "$last" ]]; then stable=$((stable + 1)); else stable=0; fi
  [[ $stable -ge 10 && "$count" -gt 0 ]] && break
  last=$count; sleep 2
done
sleep 3

OUT_FILE="$WORK/out/events.jsonl"; [[ -f "$WORK/out/actual.jsonl" ]] && OUT_FILE="$WORK/out/actual.jsonl"
python3 -I scripts/check_filebeat_events.py "$OUT_FILE" "$WORK/truth/lines.jsonl" ${EXPECT_PARSED:+--expect-parsed}
