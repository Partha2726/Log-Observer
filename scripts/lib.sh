# Shared helpers for the operational scripts (sourced, not executed).
cd "$(dirname "${BASH_SOURCE[0]}")/.."
ENV_FILE=deploy/.env
[[ -f "$ENV_FILE" ]] || cp deploy/.env.example "$ENV_FILE"
# Values from deploy/.env, unless the caller already set them (make demo passes the dataset size).
while IFS= read -r line || [[ -n "$line" ]]; do
  if [[ -z "$line" || "$line" == \#* || "$line" != *=* ]]; then continue; fi
  key="${line%%=*}"
  if [[ -z "${!key+x}" ]]; then export "$key=${line#*=}"; fi
done < "$ENV_FILE"
COMPOSE=(docker compose -f deploy/docker-compose.yml --env-file "$ENV_FILE")
ES_HOST_URL="${ES_HOST_URL:-http://localhost:9200}"
PATTERN="${ES_INDEX_PATTERN:-logs-flow-*}"

es_admin() {
  # es_admin METHOD PATH : superuser call from the host (local operations only)
  curl -s -u "elastic:${ELASTIC_PASSWORD}" -X "$1" "${ES_HOST_URL}$2"
}

es_count() {
  # es_count [query string] : document count over the log indices, 0 when there are none
  local q="${1:-}"
  local url="/${PATTERN}/_count?ignore_unavailable=true&allow_no_indices=true"
  [[ -n "$q" ]] && url="${url}&q=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$q")"
  curl -s -u "backend_reader:${ES_PASSWORD}" "${ES_HOST_URL}${url}" \
    | python3 -c 'import json, sys; print(json.load(sys.stdin).get("count", 0))'
}

delete_log_indices() {
  # Elasticsearch refuses wildcard deletes (action.destructive_requires_name), so resolve the names first.
  local names
  names=$(curl -s -u "elastic:${ELASTIC_PASSWORD}" "${ES_HOST_URL}/_resolve/index/${PATTERN}?expand_wildcards=open,closed" \
    | python3 -c 'import json, sys; print(",".join(i["name"] for i in json.load(sys.stdin).get("indices", [])))')
  if [[ -n "$names" ]]; then
    echo "deleting indices: ${names}"
    local out
    out=$(es_admin DELETE "/${names}")
    [[ "$out" == *'"acknowledged":true'* ]] || { echo "index deletion failed: $out" >&2; return 1; }
  else
    echo "no ${PATTERN} indices to delete"
  fi
}

truncate_derived_tables() {
  # Derived Postgres data only (trace*, anomaly, ai_*); never users, roles or Flyway history.
  "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USERNAME:-ielop}" -d "${DB_NAME:-ielop}" -q <<'SQL'
DO $$
DECLARE t text;
BEGIN
  FOR t IN SELECT tablename FROM pg_tables
           WHERE schemaname = 'public'
             AND (tablename LIKE 'trace%' OR tablename = 'anomaly' OR tablename LIKE 'ai\_%')
  LOOP
    EXECUTE format('TRUNCATE TABLE %I CASCADE', t);
  END LOOP;
END $$;
SQL
}
