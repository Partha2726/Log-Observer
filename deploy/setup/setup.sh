#!/usr/bin/env bash
# One-shot Elasticsearch set-up (spec section 6.6). Idempotent: safe to run on every start.
#   - sets the kibana_system password
#   - creates the least-privilege roles and users logstash_writer and backend_reader
#   - installs the 30-day ILM policy and the logs-flow-* index template (sections 6.3 and 9.2)
set -euo pipefail

ES="${ES_URL:-http://elasticsearch:9200}"
PATTERN="${ES_INDEX_PATTERN:-logs-flow-*}"

es() {
  # es METHOD PATH [JSON]
  local method="$1" path="$2" body="${3:-}"
  local out code
  if [[ -n "$body" ]]; then
    out=$(curl -s -w '\n%{http_code}' -u "elastic:${ELASTIC_PASSWORD}" -X "$method" "$ES$path" \
      -H 'Content-Type: application/json' -d "$body")
  else
    out=$(curl -s -w '\n%{http_code}' -u "elastic:${ELASTIC_PASSWORD}" -X "$method" "$ES$path")
  fi
  code="${out##*$'\n'}"
  if [[ "$code" -lt 200 || "$code" -ge 300 ]]; then
    echo "setup: $method $path failed with HTTP $code: ${out%$'\n'*}" >&2
    return 1
  fi
}

echo "setup: waiting for Elasticsearch at $ES"
for _ in $(seq 1 60); do
  if curl -fs -u "elastic:${ELASTIC_PASSWORD}" "$ES/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null; then
    break
  fi
  sleep 2
done

echo "setup: kibana_system password"
es POST /_security/user/kibana_system/_password "{\"password\":\"${KIBANA_SYSTEM_PASSWORD}\"}"

echo "setup: role logstash_writer (create_doc, create_index, manage on $PATTERN)"
es PUT /_security/role/logstash_writer "{
  \"cluster\": [\"monitor\"],
  \"indices\": [{\"names\": [\"$PATTERN\"], \"privileges\": [\"create_doc\", \"create_index\", \"manage\"]}]
}"

echo "setup: role backend_reader (read, view_index_metadata on $PATTERN)"
es PUT /_security/role/backend_reader "{
  \"cluster\": [\"monitor\"],
  \"indices\": [{\"names\": [\"$PATTERN\"], \"privileges\": [\"read\", \"view_index_metadata\"]}]
}"

echo "setup: users logstash_writer and backend_reader"
es PUT /_security/user/logstash_writer "{\"password\":\"${LOGSTASH_WRITER_PASSWORD}\",\"roles\":[\"logstash_writer\"],\"full_name\":\"Logstash writer\"}"
es PUT /_security/user/backend_reader "{\"password\":\"${ES_PASSWORD}\",\"roles\":[\"backend_reader\"],\"full_name\":\"Log Observer backend\"}"

echo "setup: ILM policy logs-flow-30d"
es PUT /_ilm/policy/logs-flow-30d "$(cat /es/ilm-policy.json)"

echo "setup: index template logs-flow ($PATTERN)"
es PUT /_index_template/logs-flow "$(sed "s/\"logs-flow-\*\"/\"${PATTERN}\"/" /es/index-template.json)"

echo "setup: done"
