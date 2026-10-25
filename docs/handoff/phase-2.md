# Phase 2 hand-off: rules and AI (T19–T31)

Date: 2026-10-12. Spec: `docs/IMPLEMENTATION_SPEC.md` v2.1. One commit per task, `T19` to `T31`, on `main` (local only, not pushed). Decisions are in `docs/DECISIONS.md`, section "Phase 2 (agent run)". Open items are under its "Blocked" heading.

## What was built

- **Generator (T19):** all 18 scenarios of section 5.5. The four incident scenarios (brute force, concurrent sessions, authorization probing, scanner traffic) are written to `incidents.jsonl`, and approval limits to `users.jsonl`.
- **Rules (T20–T21):** R01–R15 in `detection`, with R02 implemented but disabled ("R02 disabled: approvers may execute their own approvals in the demo company; enable per company policy").
  - Rules refer to roles and stages by their position in the flow definition, never by the demo's names.
  - Trace rules run after every assembly. Scan rules (R06, R07, R08, R14, R15) run in a `RULE_SCAN` job every 5 minutes and from `POST /api/admin/rule-scan`.
  - Findings are upserted by dedup key, and every finding cites its evidence document IDs.
  - `V3__detection.sql` creates the `anomaly` and `user_limit` tables. Approval limits are imported through `POST /api/admin/user-limits/import`.
- **Anomaly API and review (T22):** list, detail and `PATCH` endpoints following the section 10.2 state machine, with optimistic locking. A stale `version` or an invalid transition returns 409; reopening is ADMINISTRATOR-only.
  - Two fixes came out of the first full evaluation:
    - Assembly version 2: refused probe attempts no longer distort their victims' traces.
    - R09 needs a minimum baseline before it fires.
- **Audit (T23):** `V4__audit.sql` and `JpaAuditPublisher`, one row per audited action. This covers the tool's own logins, reviews, admin actions, AI requests and results, assistant messages and tool calls, and the retention run. `GET /api/audit` is ADMINISTRATOR-only.
- **AI (T24–T27):**
  - `AiGateway` with two implementations: `FakeAiGateway` (deterministic, offline, the default) and `OpenAiCompatibleGateway` on Spring AI 1.1.8. The OpenAI-compatible gateway has timeouts, retries once on 429 or 5xx, uses structured output, and runs tool calls on the server.
  - Start-up validation: a non-HTTPS URL stops the start; a missing key gives `DISABLED`. Also an `ai` health component and a per-user daily token budget. `V5__assistant.sql` creates the AI tables.
  - `TraceContextBuilder` selects evidence deterministically (at most 40 lines, `ev1…evN`), and `Redactor` removes sensitive values before any call.
  - Summary pipeline: prompt `sum-1` with its schema (checksummed), `OutputValidator`, `AI_SUMMARY` jobs with one retry, and caching.
  - Assistant: conversations visible to their owner only, prompt `chat-1`, five read-only audited tools (at most 5 calls per turn), and citations kept only when the conversation issued them.
- **Frontend (T28–T30):**
  - AI overview panel with all six states and clickable citations that highlight their line, plus the assistant drawer. AI text renders as text, never HTML.
  - Dashboard (Recharts) and the Anomalies page with review.
  - Admin page: health card, re-assembly and rule-scan buttons, approval-limit import, audit log.
- **Operations (T30–T31):**
  - Resumable `BULK_REASSEMBLE` (`POST /api/admin/reassemble`) and `GET /api/jobs/{id}`; expired leases are recovered every minute.
  - Nightly retention and the section 8.10 rate limits.
  - `GET /api/stats/overview` and `GET /api/admin/health`.
  - Playwright E2E tests, `make bench`, and the SEC-004 check on the demo database.

## How to run it

```bash
make demo        # stack + 10,000 flows over 7 days + ingestion + assembly (trace rules) + rule scan
make e2e         # Playwright E2E-001…003 against the running stack (AI_PROVIDER=fake)
make eval        # evaluation/report.md (attribution, stage status, first failure, rule recall, NORMAL false positives)
make bench       # evaluation/bench.md (latency, ingestion time, memory)
make test        # generator, backend, pipeline and frontend tests (stop the stack first on a 4 GB Docker VM)
make ai-live-check   # optional: one live structured-output call and one tool call (needs AI_API_KEY)
```

UI: http://localhost:5173. Users (dev profile only): `devops.dev` / `DevPassword123!` and `admin.dev` / `DevPassword123!`.

## Results (actual output, 2026-10-12)

`make demo`: 707,026 documents, 0 parse failures, 10,182 flowRefs assembled, rule scan COMPLETED, "Demo ready in 498 s".

Findings by rule after the demo:

| R01 | R03 | R04 | R05 | R06 | R07 | R08 | R09 | R10 | R11 | R12 | R13 | R14 | R15 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 88 | 88 | 88 | 356 | 89 | 88 | 99 | 347 | 88 | 88 | 176 | 89 | 88 | 267 |

`make eval`, all 10,088 flows (`evaluation/report.md`):

| Metric | Value | Target |
| --- | --- | --- |
| OBSERVED lines correct | 99.29% | ≥ 99% |
| CORRELATED precision | 92.73% | ≥ 85% |
| Stage status (all stages exact) | 100.00% | ≥ 98% |
| First-failure stage | 100.00% | ≥ 95% |
| Rule recall, each scenario | 100% for all scenarios except SLOW_APPROVE (95.5%, 84/88) | ≥ 90% |
| False positives on NORMAL | 0.75% (64/8,500, all R09); 0.00% without R09 and R12 | ≤ 2% |

`make bench` (`evaluation/bench.md`, 200 sequential requests each):

| Measurement | p50 | p95 | Target |
| --- | --- | --- | --- |
| Stored trace GET | 4 ms | 10 ms | ≤ 300 ms |
| On-demand assembly (rebuild, rules included) | 96 ms | 194 ms | ≤ 2 s |
| Flow search | 14 ms | 49 ms | ≤ 1 s |
| Log search (reported only) | 58 ms | 129 ms | — |

Ingestion of the dataset took 2.1 min (target ≤ 15 min). Peak memory of the stack was 2.76 GiB (target ≤ 8 GB).

`make e2e`: 3 passed (E2E-001 core journey, E2E-002 offline, E2E-003 anomaly review and audit).

`scripts/check-no-clear-accounts.sh` (SEC-004): no clear account number in any text, JSON or array column.

`make test` (232 s, exit 0):

- generator: 25 tests, 1 skipped (the same skip as in phase 1)
- backend: 218 tests, 0 failures. This covers every RULE-, AUD-, AI-, SEC-, JOB- and API- test the tasks name, plus ArchUnit ARCH-001…004.
- pipeline: all fixtures OK, 15,957/15,957 events, 0 parse failures, no duplicates on replay
- frontend: 77 tests

Each task commit was also verified on its own: backend `verify` in a clean worktree at that commit.

## Review follow-up (2026-10-12)

1. **Provider.** `deploy/.env` uses `AI_PROVIDER=openai` with your Gemini key, and `application.yml` now defaults to `openai` (section 8.3).
   - Gemini 3 needs each tool call's `thought_signature` sent back. The gateway now keeps it (`ToolCallExtras`).
   - Live check on `gemini-3.5-flash-lite`: one structured-output call and one tool call both pass.
   - Live check on `gemini-3.8-flash`: structured output passed in the first run. The tool call could not be confirmed, because Google kept answering 503 "high demand" or timing out. Run `make ai-live-check` again later.
   - `make e2e` assumes `AI_PROVIDER=fake`, since E2E-002 is the offline journey.
2. **Assembly version 2** is kept, for the reasons in DECISIONS.md ("Phase 2 review").
3. **`DashboardPlaceholder.tsx`** is deleted.

Not started: phase 3 (T32 onwards), per your instruction to stop at the end of phase 2.
