# Runbook

Stub created in T01. T36 completes it (start-up, reset, and failures F-01 to F-30 from spec section 19).

## Start and stop

```bash
make up      # creates deploy/.env from deploy/.env.example on first run, then starts the stack
make down
make logs
```

- Backend health: `curl -s localhost:8080/api/health`
- Frontend: http://localhost:5173

## Tests

```bash
make test    # generator, backend, pipeline and frontend tests
```

## Data

```bash
make demo          # default dataset (10,000 flows, 7 days ending yesterday), ingestion, assembly
make generate      # .env settings (GEN_FLOWS, GEN_DAYS, GEN_SEED, GEN_START, LOG_TIMEZONE)
make ingest-check  # ground-truth count vs logs-flow-* count, plus parse failures
make reingest      # delete logs-flow-* and the Filebeat registry, ship data/logs again
make reset         # stop the stack, delete indices, registry, data/ and derived Postgres tables
make eval          # evaluation/report.md (attribution, stages, rule recall and false positives)
make bench         # evaluation/bench.md (latency p50/p95, ingestion time, peak memory)
make e2e           # Playwright journeys (stack running with AI_PROVIDER=fake in deploy/.env)
scripts/check-no-clear-accounts.sh   # SEC-004 on the running database
```

## Jobs, rules and AI

- Rule scans run every 5 minutes over the last 24 hours; `make demo` also queues one scan over the generated
  range. Manual: Admin page "Run rule scan", or `POST /api/admin/rule-scan?from=&to=` (ADMINISTRATOR).
- Bulk re-assembly: Admin page "Re-assemble range" or `POST /api/admin/reassemble?from=&to=`; a job whose
  worker dies resumes from its last page after the lease (10 min) expires. Job status: `GET /api/jobs/{id}`.
- Approval limits for R10: `python3 -I scripts/import_user_limits.py` or the Admin page upload (`users.jsonl`).
- AI: `deploy/.env` sets the provider (`fake`, or `openai` with `AI_BASE_URL`, `AI_MODEL`, `AI_API_KEY`); after a
  change, `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d backend`. A provider 503
  ("high demand") or timeout fails that summary with `AI_PROVIDER_ERROR`; try again or switch the model.
  `AI_ENABLED=false` switches it off; without `AI_API_KEY` the `openai` provider reports `DISABLED` and
  the UI hides AI controls. Per-user budget: 200,000 tokens per UTC day. Retention runs nightly at 03:30.

## Common failures

- **Elasticsearch unhealthy or OOM-killed.** Lower `ES_HEAP` (and `LS_HEAP`) in `deploy/.env`; on a 4 GB
  Docker VM `ES_HEAP=768m` and `LS_HEAP=384m` work.
- **`setup` exits 1 with "would cause data streams … to no longer match".** A `logs-flow-*` data stream exists
  (created before the template). Delete it with `DELETE _data_stream/<name>` as the `elastic` user and run
  `make up` again.
- **Ingestion stuck below the ground-truth count.** `docker logs log-observer-logstash` for grok or output
  errors; `make reingest` replays from scratch. Parse failures are printed by `make ingest-check`.
- **Search shows no flows.** The default range is the last 7 days; generated data from an older `GEN_START`
  needs a custom range.
- **Backend starts but traces return 503 `ES_UNAVAILABLE`.** Elasticsearch is down or the `backend_reader`
  password in `deploy/.env` (`ES_PASSWORD`) does not match; stored traces still open.
- **429 `RATE_LIMITED`.** Login is limited to 10 per minute per address, assistant messages to 20 per minute
  per user, summary requests to 30 per hour per user (`app.rate-limits.*`).
- **AI overview FAILED `AI_PROVIDER_ERROR`.** The provider timed out or answered 429/5xx twice; the trace,
  search and anomaly pages keep working. Switch to `AI_PROVIDER=fake` and restart the backend for an offline demo.
- **Elasticsearch disk full (F-30).** Free disk, then clear the block:
  `PUT logs-flow-*/_settings {"index.blocks.read_only_allow_delete": null}`.
