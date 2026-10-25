#!/usr/bin/env bash
# Logstash pipeline tests (spec section 20, PARSE-001 to PARSE-010). Runs the production filters
# from deploy/logstash/pipeline/flow.conf in throwaway containers:
#   1. fixtures:  deploy/logstash/test/fixtures/cases.jsonl through stdin -> file, field by field (PARSE-001..009)
#   2. generated: a generated run through the real Filebeat config -> filters -> file; every event parsed,
#                 counts equal to the ground truth (zero parse failures on generated data)
#   3. template:  the ILM policy and index template applied to a throwaway Elasticsearch; field types (ES-001)
#   4. replay:    the fixtures indexed twice into it; the count is unchanged (PARSE-010)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
set -a; source deploy/.env.example; [[ -f deploy/.env ]] && source deploy/.env; set +a
VERSION="${ELASTIC_VERSION}"
WORK="$ROOT/data/test/pipeline"
KEY="test-counterparty-key"
NET=log-observer-pipetest
ES=log-observer-pipetest-es

cleanup() { docker rm -f "$ES" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
rm -rf "$WORK" && mkdir -p "$WORK/out" && chmod a+rwx "$WORK/out"

logstash() {
  # logstash <conf> [extra docker args...] ; stdin is passed through
  local conf="$1"; shift
  docker run --rm -i "$@" \
    -e LS_JAVA_OPTS="-Xms256m -Xmx256m" -e XPACK_MONITORING_ENABLED=false \
    -e COUNTERPARTY_HASH_KEY="$KEY" -e LOG_TIMEZONE=Asia/Kolkata \
    -v "$conf:/usr/share/logstash/pipeline/logstash.conf:ro" \
    -v "$ROOT/deploy/logstash/patterns:/usr/share/logstash/patterns:ro" \
    -v "$ROOT/config/flows:/usr/share/logstash/flows:ro" \
    -v "$WORK/out:/out" \
    "docker.elastic.co/logstash/logstash:$VERSION" \
    logstash --log.level=warn -f /usr/share/logstash/pipeline/logstash.conf
}

fixture_input() {
  python3 -I -c '
import json, sys
for line in open(sys.argv[1], encoding="utf-8"):
    c = json.loads(line)
    event = {"message": c["message"], "test_case": c["id"], "log": {"file": {"path": c["path"]}, "offset": c["offset"]}}
    if c.get("format"):
        event["log_format"] = c["format"]
    if c.get("service"):
        event["service"] = {"name": c["service"]}
    print(json.dumps(event))
' deploy/logstash/test/fixtures/cases.jsonl
}

echo "== 1. fixtures (PARSE-001..009)"
python3 -I scripts/build_test_pipeline.py stdin-file "$WORK/fixtures.conf"
fixture_input | logstash "$WORK/fixtures.conf" >"$WORK/fixtures.log" 2>&1 || { cat "$WORK/fixtures.log"; exit 1; }
python3 -I scripts/check_pipeline_fixtures.py deploy/logstash/test/fixtures/cases.jsonl "$WORK/out/actual.jsonl" "$KEY"

echo "== 2. generated run through Filebeat and the real filters"
python3 -I scripts/build_test_pipeline.py beats-file "$WORK/generated.conf"
PIPELINE_CONF="$WORK/generated.conf" EXPECT_PARSED=1 COUNTERPARTY_HASH_KEY="$KEY" FLOWS="${FLOWS:-200}" scripts/test-filebeat.sh

echo "== 3. index template and ILM policy (ES-001)"
docker network create "$NET" >/dev/null
docker run -d --name "$ES" --network "$NET" --network-alias es -e discovery.type=single-node -e xpack.security.enabled=false \
  -e xpack.ml.enabled=false -e ES_JAVA_OPTS="-Xms512m -Xmx512m" "docker.elastic.co/elasticsearch/elasticsearch:$VERSION" >/dev/null
for _ in $(seq 1 90); do
  docker exec "$ES" curl -fs "localhost:9200/_cluster/health?wait_for_status=yellow&timeout=2s" >/dev/null 2>&1 && break; sleep 2
done
es_put() { docker exec -i "$ES" curl -fs -XPUT "localhost:9200$1" -H 'Content-Type: application/json' --data-binary @- >/dev/null; }
es_put /_ilm/policy/logs-flow-30d < deploy/elasticsearch/ilm-policy.json
es_put /_index_template/logs-flow < deploy/elasticsearch/index-template.json
docker exec "$ES" curl -fs -XPUT localhost:9200/logs-flow-es001 >/dev/null
docker exec "$ES" curl -fs localhost:9200/logs-flow-es001/_mapping | python3 -I scripts/check_index_mapping.py
policy=$(docker exec "$ES" curl -fs localhost:9200/logs-flow-es001/_settings | python3 -c 'import json,sys; print(next(iter(json.load(sys.stdin).values()))["settings"]["index"]["lifecycle"]["name"])')
if [[ "$policy" == "logs-flow-30d" ]]; then echo "ok   ES-001 ILM policy $policy attached"; else echo "FAIL ES-001 ILM policy: $policy"; exit 1; fi

echo "== 4. replay into Elasticsearch (PARSE-010)"
python3 -I scripts/build_test_pipeline.py stdin-es "$WORK/replay.conf" logs-flow-parse010
count() { docker exec "$ES" sh -c 'curl -s -XPOST localhost:9200/logs-flow-parse010/_refresh >/dev/null; curl -s localhost:9200/logs-flow-parse010/_count' | python3 -c 'import json,sys; print(json.load(sys.stdin)["count"])'; }
fixture_input | logstash "$WORK/replay.conf" --network "$NET" >"$WORK/replay1.log" 2>&1
first=$(count)
fixture_input | logstash "$WORK/replay.conf" --network "$NET" >"$WORK/replay2.log" 2>&1
second=$(count)
cases=$(wc -l < deploy/logstash/test/fixtures/cases.jsonl | tr -d ' ')
echo "first ingest: $first documents; second ingest: $second documents; fixture events: $cases"
kind=$(docker exec "$ES" curl -s localhost:9200/_resolve/index/logs-flow-parse010 | python3 -c 'import json,sys; r=json.load(sys.stdin); print("index" if r["indices"] and not r["data_streams"] else "stream")')
if [[ "$kind" == "index" ]]; then echo "ok   logs-flow-parse010 is a plain index (template beats the built-in logs-*-* data stream template)"; else echo "FAIL logs-flow-parse010 became a data stream"; exit 1; fi
if [[ "$first" == "$cases" && "$second" == "$first" ]]; then echo "ok   PARSE-010 replay creates no duplicates"; else echo "FAIL PARSE-010"; exit 1; fi
echo "pipeline tests passed"
