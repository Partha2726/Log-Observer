# Phase 1 hand-off: core pipeline (T01–T18)

Date: 2026-10-12. Spec: `docs/IMPLEMENTATION_SPEC.md` v2.1. Commits: one per task, `T01` to `T18`, on `main` (local only, not pushed).

## What was built

- **Generator** (`generator/`, Java 21, Log4j2 2.24.3): deterministic fake logs for the four Acme Cloud services in the four section 3 formats. It writes with Log4j2's own layouts (Pattern, JsonTemplate, RFC 5424 through a small host/PID wrapper, Pattern with `withLocation`) on a simulated clock. It covers users with approval limits, sessions ending in sign-out or expiry, the NORMAL flow, `FAIL_DB_TIMEOUT_APPROVE`, `FAIL_DB_DOWN_CREATE`, `FAIL_VALIDATION_CREATE`, `FAIL_PROVIDER_TIMEOUT_EXECUTE`, `APPROVER_REJECT` and `SESSION_TIMEOUT`. It adds the section 5.4 ID presence rates, multi-line SQL, XML and stack traces, about 30% noise, and the ground-truth files (`flows`, `lines`, `users`, `incidents`).
- **Ingestion** (`deploy/`): Elasticsearch, Logstash, Filebeat and Kibana 9.5.2 in Compose (Kibana behind profile `tools`). A `setup` container creates the least-privilege `logstash_writer` and `backend_reader` users and installs the index template and the 30-day ILM policy. Filebeat has four `filestream` inputs, multiline for format D. The Logstash pipeline does ECS parsing, takes flowRef patterns and business fields from `config/flows/*.yaml`, maps format D prefixes to actions from `patterns/flow`, hashes and masks counterparty accounts, keeps parse failures, and uses a fingerprint `_id` with create-only writes.
- **Backend** (Spring Boot 3.5.16):
  - Testcontainers replace H2; ArchUnit checks package boundaries.
  - `logsearch`: the Elasticsearch 9.x client, capped and with timeouts.
  - `flowdef`: YAML plus JSON Schema plus semantic checks, and `GET /api/flows/definitions`.
  - `V2__trace.sql`.
  - `trace`: assembly steps 1–12 with section 7.4 confidence, config hash, staleness, and advisory-locked single-transaction persistence.
  - `graph_version 1` builder.
  - Search, logs, user activity, request, trace, events and graph APIs, with section 8.7 validation and errors.
  - OpenAPI in the dev profile and a generated `docs/API.md`.
- **Frontend** (React 18 + Vite): TanStack Query, zod schemas for every contract, shared four-state components, 401 → `/login?next=`. Pages: Search (section 12.4 auto-detect plus flow filters), User activity, Logs, Request, and Trace view (header, React Flow swimlane graph, evidence panel).
- **Operations**: `make up/down/logs/generate/ingest-check/reingest/reset/test/demo/eval`, `scripts/demo.sh`, `evaluation/score.py`, CI workflow (`make test`).

Industry neutrality: stage names, roles, services, event names, success and reject markers, sign-out actions, flowRef patterns and business fields come from the flow definition. The test-only two-stage definition (`backend/src/test/resources/flows/test-two-stage.yaml`, a helpdesk ticket flow) assembles and graphs with no code change (TRACE-019).

## How to run it

```bash
make demo        # stack + 10,000 flows over 7 days (ending yesterday) + ingestion + assembly
make eval        # writes evaluation/report.md
make test        # generator, backend, pipeline and frontend tests
```

UI: http://localhost:5173, user `devops.dev` / `DevPassword123!` (dev profile only). API docs: http://localhost:8080/api/docs/ui.
Prerequisites: Docker with about 4 GB for the stack (on this machine `deploy/.env` sets `ES_HEAP=768m`, `LS_HEAP=384m`), Python 3, and Java 21 and Node 20 for the tests. The stack is left running with the demo dataset.

## Test results (actual output)

`make demo` (2026-10-12, default dataset):

```
== 1/5 starting the stack
== 2/5 clearing derived data and generating 10000 flows over 7 days from 2026-10-04T08:00 (Asia/Kolkata)
== 3/5 waiting for ingestion
ingest-check: complete (662829 documents, 0 parse failures)
== 4/5 assembling every flow (phase 1 stand-in for POST /api/admin/reassemble)
assembled 10000 flows in 136 s: {'COMPLETED': 8750, 'FAILED': 1000, 'REJECTED': 250}
== 5/5 rule scan: phase 2 (T21); skipped
Demo ready in 304 s.
```

`make eval` on all 10,000 flows (`evaluation/report.md`, 57 s):

| Metric | Value | Target | Met |
| --- | --- | --- | --- |
| OBSERVED lines correct | 100.00% | ≥ 99% | yes |
| CORRELATED precision | 92.52% | ≥ 85% | yes |
| Stage status (all stages of a flow exact) | 100.00% | ≥ 98% | yes |
| First-failure stage | 100.00% | ≥ 95% | yes |

Line recall 99.86% (439,880 / 440,480), first-failure component 98.90%, 0 evidence lines unmapped, 0 errors.

`make test` (2 min 18 s, exit 0):

```
generator:  Tests run: 20, Failures: 0, Errors: 0, Skipped: 1      (GEN-009 skipped: no .denylist on this machine)
backend:    Tests run: 131, Failures: 0, Errors: 0, Skipped: 0
pipeline:   23/23 fixture cases passed; generated run: parse failures 0, counts equal ground truth;
            ES-001 44 field types and 4 field options match; ILM policy attached; PARSE-010 replay creates no duplicates
frontend:   Test Files 12 passed (12), Tests 51 passed (51)
```

T17 acceptance: `scripts/check_first_failures.py 10` gave "10/10 first-failure nodes match ground truth" on the demo dataset. A headless screenshot of a `FAIL_DB_TIMEOUT_APPROVE` trace was reviewed.

Phase 1 gate (section 22): GEN-001…008 pass (GEN-009 skipped as specified without a deny-list); PARSE-001…010 and ES-001 pass; TRACE-001…033 and CONC-001 pass; API-001…010, 013, 020, 021 and SEC-001 pass; UI-001…005, 007 and 010 pass; `make demo` and the EVAL-001 attribution and status targets are met; `docs/API.md` is generated.

## Known gaps

- **Measured volume is below the spec's estimate.** The default run makes 662,829 events and 184 MB of logs, against the section 5.8 estimate of about 1.4 M events (44 flow lines per flow, not 60–100). Recorded in `DECISIONS.md`.
- **GEN-009 did not run.** `.denylist` is untracked and not present here. Add it locally (or set the `DENYLIST` CI secret) for the check to run.
- **Live API checks I could not cover.** API-011 and API-012 (anomalies) and every rule, AI, audit, admin and rate-limit item are phase 2. `hasAnomaly=true` returns no flows and `anomalies` arrays are empty until V3.
- **No bulk re-assembly endpoint yet.** `make demo` assembles through the trace API (`scripts/assemble_all.py`); `POST /api/admin/reassemble` and `/jobs` arrive in T30.
- **First-failure component.** 98.9% correct: about 1% of failed flows lose the legacy billing exception line (no request ID, flowRef or linked neighbour), so the workflow component is named instead. The stage is still right.
- **Login response shape.** `POST /api/auth/login` keeps the salvaged body `{accessToken, tokenType, expiresInSeconds, user}`, not section 8.8's `{token, expiresAt, roles}`.
- **Graph labels.** EXTERNAL nodes are labelled with the external correlation ID and provider status, not "PaymentProvider", because message text is not stored in Postgres. Gateway nodes can sort before the component that caused their 5xx (access-log times have second precision).
- **Smaller Docker VM.** Docker Desktop here has about 4 GB, not 8 GB; heaps are lowered in `deploy/.env`. The frontend dev server uses about 214 of its 256 MB cap.
- **Not measured.** `make bench`, Playwright E2E and Kibana (not started) belong to T31 or the optional `tools` profile.
- **Frontend in the stack.** The container runs the Vite dev server (salvaged Dockerfile); a production build image is not part of phase 1.

## DECISIONS.md entries added in phase 1

52 entries are dated 2026-10-11 under "Phase 1 (agent run)"; there are no "Blocked" items. The main ones:

- Section 16 defaults taken; Java via Homebrew `openjdk@21` and `./mvnw`; 4 GB Docker overrides.
- Migration numbering V2–V5.
- Pins: Boot 3.5.16; Elastic 9.5.2; test libraries; Log4j2 2.24.3 and the generator libraries; networknt 1.5.9; frontend libraries.
- Generator design: `SimClock`, the RFC 5424 wrapper, `%c{1.}` against the example line, file names, scenario quotas, failing-component convention, measured volume.
- Pipeline: inode file identity; Logstash reads flow YAMLs; gateway action label; format D prefixes; masking; template priority and `log_text` analyzer; Filebeat's multiline offset quirk; fixed wildcard index deletion.
- Assembly interpretations (status from OBSERVED lines, first-failure preference, FAILED-first overall status, thread and time-only rules); graph mapping; API conventions and semantics; generated API docs.
- Frontend data rules and contract fixtures; search detection; trace view layout; evaluation and demo design.

## Waiting for

Owner go-ahead before phase 2 (T19–T31). Phase 2 also needs the owner's answers on open question 4 (AI provider and model) and open question 2 (R02 policy; the default is disabled).
