#!/usr/bin/env bash
# make bench (sections 13 and 17, PERF-001…005): measures the running stack after make demo and writes
# evaluation/bench.md. Ingestion time is the span of ingested_at over the indexed documents.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
span=$(curl -s -u "backend_reader:${ES_PASSWORD}" -H 'Content-Type: application/json' \
  "${ES_HOST_URL}/${PATTERN}/_search?size=0&ignore_unavailable=true" \
  -d '{"aggs":{"min":{"min":{"field":"ingested_at"}},"max":{"max":{"field":"ingested_at"}}},"track_total_hits":true}' \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); a=r["aggregations"]; print(int(((a["max"]["value"] or 0)-(a["min"]["value"] or 0))/1000), r["hits"]["total"]["value"])')
python3 -I evaluation/bench.py --ingest-seconds "${span% *}" --ingested-docs "${span#* }" "$@"
