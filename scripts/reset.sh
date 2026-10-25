#!/usr/bin/env bash
# Reset (spec section 6.5): stop the stack, delete the log indices, the Filebeat registry,
# data/logs and data/ground-truth, and truncate the derived Postgres tables (trace*, anomaly, ai_*).
# Never touches users, roles or the Flyway history.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

echo "reset: starting Elasticsearch and Postgres to clear derived data"
"${COMPOSE[@]}" up -d --wait elasticsearch postgres setup >/dev/null 2>&1 || "${COMPOSE[@]}" up -d --wait elasticsearch postgres
delete_log_indices
truncate_derived_tables
"${COMPOSE[@]}" down
docker volume rm -f log-observer_filebeat-data >/dev/null
rm -rf data/logs data/ground-truth
echo "reset: done (users, roles and Flyway history untouched)"
