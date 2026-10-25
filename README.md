# Log Observer

Search a user, open one of their flows (sign-in, a business interaction, sign-out) traced across services and
log formats, and see it as a graph with the failing step, the evidence lines and their confidence.

The build contract is `docs/IMPLEMENTATION_SPEC.md` (v2.1). Phases 1 and 2 are built: the log generator,
Filebeat, Logstash and Elasticsearch, trace assembly and the trace graph; anomaly rules R01–R15 with review
and audit; an AI overview of a trace and a developer assistant with read-only tools (a deterministic `fake`
provider by default, or any OpenAI-compatible API); the Dashboard, Search, Logs, User, Request, Trace,
Anomalies and Admin pages. All data is generated for the fictional company Acme Cloud; no real logs are used.

## Quick start

Needs Docker (Compose v2) with about 4 GB of memory for the stack, Python 3, and (for the tests) Java 21
and Node 20.

```bash
cp deploy/.env.example deploy/.env  # local placeholders only; do not commit this file
make demo        # stack + 10,000 generated flows over 7 days + ingestion + assembly + rule scan (about 10 min)
```

The example file has no real keys and uses the offline `AI_PROVIDER=fake` by default. Replace its
placeholder secrets before sharing the stack or connecting it to non-local services.

Then open http://localhost:5173 and log in as `devops.dev` / `DevPassword123!`, or `admin.dev` /
`DevPassword123!` for the Admin page (dev profile only). AI runs offline with `AI_PROVIDER=fake`; to use a
real OpenAI-compatible provider set `AI_PROVIDER=openai`, `AI_BASE_URL`, `AI_MODEL` and `AI_API_KEY` in
`deploy/.env` (only redacted, generated evidence is sent).
`make demo` prints one example flow per scenario; `DEMO_FLOWS=1000 DEMO_DAYS=1 make demo` is a quicker run.

Other targets (`make help` is not provided; see the `Makefile`):

| Target | Does |
| --- | --- |
| `make up` / `make down` / `make logs` | Start, stop, follow the stack (`deploy/.env` is created from `deploy/.env.example` on first use) |
| `make generate` | Generate logs with the `.env` settings and ship them from scratch |
| `make ingest-check` | Compare the ground-truth event count with Elasticsearch |
| `make reingest` / `make reset` | Replay the files / delete all generated and derived data |
| `make test` | Generator, backend (Testcontainers), pipeline and frontend tests |
| `make eval` | Score the running stack against the ground truth (attribution, stages, rules); writes `evaluation/report.md` |
| `make e2e` | Playwright journeys against the running stack (E2E-001…003) |
| `make bench` | Latency, ingestion time and memory of the running stack; writes `evaluation/bench.md` |
| `make ai-live-check` | One live structured-output call and one tool call against the configured provider (needs `AI_API_KEY`) |

## Layout

`generator/` (Java 21, Log4j2), `deploy/` (Compose, Filebeat, Logstash, Elasticsearch template),
`config/flows/` (flow definitions), `backend/` (Spring Boot), `frontend/` (React, Vite), `evaluation/`,
`scripts/`, `docs/` (`API.md`, `DECISIONS.md`, `RUNBOOK.md`, `handoff/`).
