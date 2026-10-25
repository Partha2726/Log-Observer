#!/usr/bin/env bash
# make demo (spec sections 14 and 24): start the stack, generate the default dataset (10,000 flows over
# 7 days, ending yesterday so the UI's default "last 7 days" range shows it), wait until ingestion is
# complete, assemble every flow, and print where to look.
#   DEMO_FLOWS / DEMO_DAYS override the dataset size (CI and quick checks use 1000 / 1); DEMO_START the first day.
# Approval limits are imported before assembly so R10 runs on every trace.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

FLOWS="${DEMO_FLOWS:-10000}"
DAYS="${DEMO_DAYS:-7}"
# DEMO_START pins the first day (two runs to compare, as in phase 3, need the same start).
START="${DEMO_START:-$(python3 -c "import datetime as d; print((d.date.today() - d.timedelta(days=${DAYS} + 1)).isoformat() + 'T08:00')")}"
started=$(date +%s)

echo "== 1/5 starting the stack"
mkdir -p data/logs data/ground-truth
"${COMPOSE[@]}" up -d --build --wait

echo "== 2/5 clearing derived data and generating ${FLOWS} flows over ${DAYS} days from ${START} (${LOG_TIMEZONE:-UTC})"
truncate_derived_tables
GEN_FLOWS="$FLOWS" GEN_DAYS="$DAYS" GEN_START="$START" scripts/generate.sh

echo "== 3/5 waiting for ingestion"
INGEST_TIMEOUT_SECONDS="${INGEST_TIMEOUT_SECONDS:-1800}" scripts/ingest-check.sh

echo "== 4/5 importing approval limits, then assembling every flow (trace rules run after each assembly)"
python3 -I scripts/import_user_limits.py
python3 -I scripts/assemble_all.py --threads "${DEMO_THREADS:-4}"

echo "== 5/5 rule scan over the generated range (user, IP and window rules)"
SCAN_FROM=$(python3 -c "import datetime as d; print((d.datetime.fromisoformat('${START}') - d.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
SCAN_TO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
job=$(python3 -I scripts/rule_scan.py --from "$SCAN_FROM" --to "$SCAN_TO")
for _ in $(seq 1 360); do
  state=$("${COMPOSE[@]}" exec -T postgres psql -At -U "${DB_USERNAME:-ielop}" -d "${DB_NAME:-ielop}" \
    -c "SELECT status FROM processing_jobs WHERE id = '$job'")
  [[ "$state" == "COMPLETED" || "$state" == "FAILED" ]] && break
  sleep 5
done
echo "  rule scan $job: $state"
"${COMPOSE[@]}" exec -T postgres psql -At -U "${DB_USERNAME:-ielop}" -d "${DB_NAME:-ielop}" -F ' ' \
  -c "SELECT '  ' || rule_id, count(*) FROM anomaly GROUP BY rule_id ORDER BY rule_id"

echo
echo "Demo ready in $(( $(date +%s) - started )) s."
echo "  UI:       http://localhost:5173  (log in as devops.dev / DevPassword123!, dev profile only)"
echo "  API docs: http://localhost:8080/api/docs/ui"
python3 -I - <<'PY'
import json
seen = {}
for line in open("data/ground-truth/flows.jsonl", encoding="utf-8"):
    f = json.loads(line)
    seen.setdefault(f["scenario"], f)
print("  Example flows (open http://localhost:5173/traces/<flowRef>):")
for scenario, f in sorted(seen.items()):
    print(f"    {scenario:<30} {f['flowRef']}  requester {f['requester']}")
PY
