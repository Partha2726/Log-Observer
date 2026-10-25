#!/usr/bin/env bash
# Runs the generator with the .env settings (GEN_FLOWS, GEN_DAYS, GEN_SEED, LOG_TIMEZONE) and ships
# the new files from scratch. The generator replaces data/logs and data/ground-truth, so Filebeat is
# stopped first and the indices and registry are reset afterwards (make reingest); otherwise old
# documents would mix with the new run and reused inodes could confuse the registry.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

mkdir -p data/logs data/ground-truth
running=$("${COMPOSE[@]}" ps -q filebeat 2>/dev/null || true)
[[ -n "$running" ]] && "${COMPOSE[@]}" stop filebeat >/dev/null
echo "generate: ${GEN_FLOWS:-1000} flows over ${GEN_DAYS:-1} day(s), seed ${GEN_SEED:-42}, legacy timezone ${LOG_TIMEZONE:-UTC}"
"${COMPOSE[@]}" --profile generate run --rm --build generator
if [[ -n "$("${COMPOSE[@]}" ps -a -q elasticsearch 2>/dev/null || true)" ]]; then
  scripts/reingest.sh
fi
