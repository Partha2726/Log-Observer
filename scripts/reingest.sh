#!/usr/bin/env bash
# Replay (spec section 6.5): delete the log indices and the Filebeat registry, then restart
# Filebeat so every file in data/logs is shipped again.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

"${COMPOSE[@]}" stop filebeat >/dev/null
"${COMPOSE[@]}" rm -f filebeat >/dev/null
delete_log_indices
echo "reingest: deleting the Filebeat registry"
docker volume rm -f log-observer_filebeat-data >/dev/null
"${COMPOSE[@]}" up -d --wait filebeat
echo "reingest: Filebeat restarted"
