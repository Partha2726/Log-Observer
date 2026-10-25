#!/usr/bin/env bash
# Ingestion completeness check (spec section 6.5). Compares the ground-truth event count
# (data/ground-truth/lines.jsonl) with the document count of logs-flow-*. Complete when the counts
# match, or when the count has been stable for 10 s and is within 0.1% (the gap is printed).
# The parse-failure count is printed every time. Exits non-zero on timeout or a larger gap.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

TIMEOUT="${INGEST_TIMEOUT_SECONDS:-1200}"
TRUTH=data/ground-truth/lines.jsonl
[[ -f "$TRUTH" ]] || { echo "ingest-check: $TRUTH not found; run make generate first"; exit 1; }
expected=$(wc -l < "$TRUTH" | tr -d ' ')

start=$(date +%s); last=-1; stable_since=$start
while true; do
  count=$(es_count)
  failures=$(es_count 'tags:_grokparsefailure OR tags:_jsonparsefailure')
  now=$(date +%s)
  echo "ingest-check: ${count}/${expected} documents, parse failures: ${failures}"
  if [[ "$count" -eq "$expected" ]]; then
    echo "ingest-check: complete (${count} documents, ${failures} parse failures)"
    exit 0
  fi
  if [[ "$count" -ne "$last" ]]; then stable_since=$now; last=$count; fi
  if (( now - stable_since >= 10 && count > 0 )); then
    gap=$(( expected - count ))
    if python3 -c "import sys; sys.exit(0 if abs($gap) <= 0.001 * $expected else 1)"; then
      echo "ingest-check: stable for 10 s within 0.1%: ${count}/${expected} (gap ${gap}), ${failures} parse failures"
      exit 0
    fi
  fi
  if (( now - start >= TIMEOUT )); then
    echo "ingest-check: timed out after ${TIMEOUT}s at ${count}/${expected}"
    exit 1
  fi
  sleep 5
done
