# Log Observer API

Generated from the OpenAPI document (`/api/docs`, dev profile) by `ApiDocsTest`; do not edit by hand.
The contracts are spec sections 7.6 and 8.7 to 8.8.

## Conventions

- Base path `/api`, JSON only. Every endpoint needs `Authorization: Bearer <JWT>` except `POST /api/auth/login`, `GET /api/health`, `GET /actuator/health` and `GET /actuator/info`.
- Times are ISO-8601 UTC with milliseconds, for example `2026-05-15T13:10:02.114Z`.
- Errors use one body: `{timestamp, status, error, message, path, code, requestId, fieldErrors}`. `requestId` equals the `X-Request-ID` response header. Codes: `VALIDATION_ERROR` 400, `UNAUTHORIZED` 401, `FORBIDDEN` 403, `NOT_FOUND` 404, `TRACE_NOT_FOUND` 404, `CONFLICT` 409, `RATE_LIMITED` 429, `INTERNAL_ERROR` 500, `ES_UNAVAILABLE` 503.
- Pages: `page` (0-based) and `size` (default 25, at most 100); body `{items, page, size, totalElements, totalPages, totalIsLowerBound}`. Elasticsearch-backed totals stop at 10,000 and then set `totalIsLowerBound`.
- Time ranges: `from` and `to`; Elasticsearch-backed lists default to the last 7 days and allow at most 31 days.
- Path IDs are validated before any query: a flowRef must match a loaded flow definition's `refPattern`, a request ID must be a UUID, and a user ID must match `app.ids.user-pattern`.
- When Elasticsearch is down, stored traces, graphs and Postgres-backed searches keep working; the rest answer 503 `ES_UNAVAILABLE`.

## Endpoints

### GET /api/admin/health

Admin health card: components, job queues, ingestion, findings and AI summaries (ADMINISTRATOR)

- `200` AdminHealth

### POST /api/admin/reassemble

Re-assemble every flow seen in a range (ADMINISTRATOR); resumable, one job at a time

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `from` | query | no | string |
| `to` | query | no | string |

- `202` Accepted

### POST /api/admin/rule-scan

Queue a rule scan over a time range (ADMINISTRATOR)

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `from` | query | no | string |
| `to` | query | no | string |

- `202` Accepted

### POST /api/admin/user-limits/import

Replace the approval limits from a users.jsonl file (ADMINISTRATOR)

Request body: object

- `200` ImportResult

### GET /api/anomalies

Anomalies, newest window first; the range matches anomalies whose window overlaps it

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `ruleId` | query | no | string |
| `severity` | query | no | string |
| `status` | query | no | string |
| `userId` | query | no | string |
| `flowRef` | query | no | string |
| `clientIp` | query | no | string |
| `from` | query | no | string |
| `to` | query | no | string |
| `page` | query | no | integer (int32) |
| `size` | query | no | integer (int32) |

- `200` PageResponseItem

### GET /api/anomalies/{id}

One anomaly with its evidence document IDs and review history

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `id` | path | yes | integer (int64) |

- `200` Detail

### PATCH /api/anomalies/{id}

Review an anomaly: NEW to CONFIRMED or DISMISSED; an administrator may reopen (to NEW). Needs the current version

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `id` | path | yes | integer (int64) |

Request body: ReviewRequest

- `200` Detail

### POST /api/assistant/conversations

Start a conversation, about one trace (flowRef) or global

Request body: NewConversation

- `201` Created

### GET /api/assistant/conversations/{id}

The conversation's messages (owner only)

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `id` | path | yes | string (uuid) |

- `200` Conversation

### POST /api/assistant/conversations/{id}/messages

Ask a question; the answer cites log lines as [ev:N]

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `id` | path | yes | string (uuid) |

Request body: NewMessage

- `200` Answer

### GET /api/audit

Audit events, newest first (ADMINISTRATOR)

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `actor` | query | no | string |
| `action` | query | no | string |
| `from` | query | no | string |
| `to` | query | no | string |
| `page` | query | no | integer (int32) |
| `size` | query | no | integer (int32) |

- `200` PageResponseAuditEvent

### POST /api/auth/login

Request body: LoginRequest

- `200` LoginResponse

### GET /api/auth/me

- `200` UserSummary

### GET /api/flows/definitions

Loaded flow definitions: stages, roles, reference pattern

- `200` array of FlowDefinitionView

### GET /api/health

- `200` map of object

### GET /api/jobs/{id}

Status of a processing job (AI_SUMMARY, RULE_SCAN, BULK_REASSEMBLE)

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `id` | path | yes | string (uuid) |

- `200` JobStatus

### GET /api/requests/{requestId}

Resolve a request ID to its flowRef, or return the request's own lines

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `requestId` | path | yes | string |

- `200` RequestLookup

### GET /api/search/flows

Flow list from assembled traces plus unassembled flowRefs found in Elasticsearch

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `userId` | query | no | string |
| `flowRef` | query | no | string |
| `requestId` | query | no | string |
| `flowId` | query | no | string |
| `stage` | query | no | string |
| `status` | query | no | string |
| `hasAnomaly` | query | no | boolean |
| `from` | query | no | string |
| `to` | query | no | string |
| `page` | query | no | integer (int32) |
| `size` | query | no | integer (int32) |

- `200` PageResponseFlowSummary

### GET /api/search/logs

Free-text log search with filters (7-day default range, 31 days at most)

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `q` | query | no | string |
| `stage` | query | no | string |
| `level` | query | no | string |
| `service` | query | no | string |
| `host` | query | no | string |
| `clientIp` | query | no | string |
| `from` | query | no | string |
| `to` | query | no | string |
| `page` | query | no | integer (int32) |
| `size` | query | no | integer (int32) |

- `200` PageResponseLogLine

### GET /api/stats/overview

Dashboard counts: flows by status and stage, anomalies by rule, errors by service, failing components, flows per day

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `from` | query | no | string |
| `to` | query | no | string |
| `flowId` | query | no | string |

- `200` Overview

### GET /api/traces/{flowRef}

Assembled trace: stages, actors, status, first failure (assembles on demand)

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `flowRef` | path | yes | string |
| `rebuild` | query | no | boolean |

- `200` Trace

### GET /api/traces/{flowRef}/ai-summary

The latest summary of the trace and its status; evidence maps evN to log document IDs

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `flowRef` | path | yes | string |

- `200` Summary

### POST /api/traces/{flowRef}/ai-summary

Request an AI overview: 200 with a cached summary, or 202 with the job that produces it

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `flowRef` | path | yes | string |

Request body: RequestBodyDto

- `200` object

### GET /api/traces/{flowRef}/events

Evidence lines with class and confidence; message text by document ID

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `flowRef` | path | yes | string |
| `stage` | query | no | string |
| `evidenceClass` | query | no | string |
| `nodeId` | query | no | string |
| `page` | query | no | integer (int32) |
| `size` | query | no | integer (int32) |

- `200` PageResponseEvidenceLine

### GET /api/traces/{flowRef}/graph

Trace graph, graph_version 1, from Postgres only

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `flowRef` | path | yes | string |

- `200` TraceGraph

### GET /api/users

- `200` array of UserSummary

### POST /api/users

Request body: CreateUserRequest

- `200` UserSummary

### GET /api/users/{userId}/activity

Sessions, failed sign-ins, flows with the user's role, permission denials

| Parameter | In | Required | Type |
| --- | --- | --- | --- |
| `userId` | path | yes | string |
| `from` | query | no | string |
| `to` | query | no | string |

- `200` UserActivity

## Schemas

### Accepted

| Field | Type |
| --- | --- |
| `jobId` | string (uuid) |

### AdminHealth

| Field | Type |
| --- | --- |
| `at` | string (date-time) |
| `components` | map of Component |
| `jobs` | map of map of integer (int64) |
| `ingest` | Ingest |
| `ingestError` | string |
| `findingsLast24h` | map of integer (int64) |
| `summariesLast24h` | map of integer (int64) |
| `alerts` | array of string |

### Answer

| Field | Type |
| --- | --- |
| `answer` | string |
| `citations` | array of Citation |
| `toolCalls` | array of ToolCall |
| `usage` | Usage |

### AuditEvent

| Field | Type |
| --- | --- |
| `id` | integer (int64) |
| `occurredAt` | string (date-time) |
| `actor` | string |
| `action` | string |
| `targetType` | string |
| `targetId` | string |
| `requestId` | string |
| `metadata` | map of object |

### Citation

| Field | Type |
| --- | --- |
| `ref` | string |
| `docId` | string |
| `stage` | string |
| `ts` | string |

### Component

| Field | Type |
| --- | --- |
| `status` | string |
| `details` | map of object |

### ComponentFailures

| Field | Type |
| --- | --- |
| `logger` | string |
| `count` | integer (int64) |

### Conversation

| Field | Type |
| --- | --- |
| `conversationId` | string (uuid) |
| `flowRef` | string |
| `createdAt` | string (date-time) |
| `messages` | array of Message |

### CreateUserRequest

| Field | Type |
| --- | --- |
| `username` | string |
| `email` | string (email) |
| `password` | string |
| `fullName` | string |
| `role` | string |

### Created

| Field | Type |
| --- | --- |
| `conversationId` | string (uuid) |

### Day

| Field | Type |
| --- | --- |
| `day` | string |
| `completed` | integer (int64) |
| `failed` | integer (int64) |
| `rejected` | integer (int64) |
| `incomplete` | integer (int64) |

### Detail

| Field | Type |
| --- | --- |
| `id` | integer (int64) |
| `ruleId` | string |
| `ruleName` | string |
| `severity` | string |
| `status` | string |
| `flowRef` | string |
| `userId` | string |
| `clientIp` | string |
| `detectedAt` | string (date-time) |
| `windowStart` | string (date-time) |
| `windowEnd` | string (date-time) |
| `details` | map of object |
| `evidenceDocIds` | array of string |
| `version` | integer (int32) |
| `reviewedBy` | string |
| `reviewNote` | string |
| `reviewedAt` | string (date-time) |
| `history` | array of Review |

### Edge

| Field | Type |
| --- | --- |
| `id` | string |
| `source` | string |
| `target` | string |
| `kind` | string |
| `label` | string |
| `evidenceIds` | array of string |

### EvidenceLine

| Field | Type |
| --- | --- |
| `docId` | string |
| `ts` | string (date-time) |
| `service` | string |
| `host` | string |
| `stage` | string |
| `sourceFile` | string |
| `level` | string |
| `logger` | string |
| `thread` | string |
| `requestId` | string |
| `evidenceClass` | string |
| `confidence` | number |
| `linkReason` | string |
| `message` | string |
| `parsed` | map of object |
| `isStacktrace` | boolean |
| `expired` | boolean |

### FailedSignIn

| Field | Type |
| --- | --- |
| `ts` | string (date-time) |
| `clientIp` | string |
| `reason` | string |
| `eventAction` | string |

### FirstFailure

| Field | Type |
| --- | --- |
| `stage` | string |
| `component` | string |
| `exception` | string |
| `httpStatus` | integer (int32) |
| `docId` | string |

### FlowDefinitionView

| Field | Type |
| --- | --- |
| `id` | string |
| `version` | integer (int32) |
| `refPattern` | string |
| `roles` | array of string |
| `stages` | array of StageView |
| `hasBusinessFields` | boolean |

### FlowSummary

| Field | Type |
| --- | --- |
| `flowRef` | string |
| `flowId` | string |
| `overallStatus` | string |
| `actors` | map of string |
| `requester` | string |
| `approver` | string |
| `executor` | string |
| `firstSeen` | string (date-time) |
| `lastSeen` | string (date-time) |
| `amount` | number |
| `currency` | string |
| `anomalyCount` | integer (int32) |
| `maxSeverity` | string |
| `assembled` | boolean |

### ImportResult

| Field | Type |
| --- | --- |
| `imported` | integer (int32) |
| `skipped` | integer (int32) |

### Ingest

| Field | Type |
| --- | --- |
| `events` | integer (int64) |
| `parseFailures` | integer (int64) |
| `parseFailureRatio` | number (double) |
| `latestEventAt` | string (date-time) |
| `latestIngestedAt` | string (date-time) |

### Item

| Field | Type |
| --- | --- |
| `id` | integer (int64) |
| `ruleId` | string |
| `ruleName` | string |
| `severity` | string |
| `status` | string |
| `flowRef` | string |
| `userId` | string |
| `clientIp` | string |
| `detectedAt` | string (date-time) |
| `windowStart` | string (date-time) |
| `windowEnd` | string (date-time) |
| `details` | map of object |
| `evidenceCount` | integer (int32) |
| `version` | integer (int32) |

### JobStatus

| Field | Type |
| --- | --- |
| `id` | string (uuid) |
| `type` | string |
| `status` | string |
| `attemptCount` | integer (int32) |
| `maxAttempts` | integer (int32) |
| `errorCode` | string |
| `createdAt` | string (date-time) |
| `startedAt` | string (date-time) |
| `completedAt` | string (date-time) |

### JsonNode

object

### Lane

| Field | Type |
| --- | --- |
| `stage` | string |
| `status` | string |
| `userId` | string |
| `startedAt` | string |
| `endedAt` | string |
| `durationMs` | integer (int64) |

### LogLine

| Field | Type |
| --- | --- |
| `docId` | string |
| `ts` | string (date-time) |
| `service` | string |
| `host` | string |
| `stage` | string |
| `sourceFile` | string |
| `level` | string |
| `logger` | string |
| `thread` | string |
| `requestId` | string |
| `flowRefs` | array of string |
| `userId` | string |
| `eventAction` | string |
| `httpStatus` | integer (int32) |
| `snippet` | string |
| `highlight` | string |

### LoginRequest

| Field | Type |
| --- | --- |
| `username` | string |
| `password` | string |

### LoginResponse

| Field | Type |
| --- | --- |
| `accessToken` | string |
| `tokenType` | string |
| `expiresInSeconds` | integer (int64) |
| `user` | UserSummary |

### Message

| Field | Type |
| --- | --- |
| `role` | string |
| `content` | string |
| `citations` | array of Citation |
| `toolCalls` | array of ToolCall |
| `createdAt` | string (date-time) |

### NewConversation

| Field | Type |
| --- | --- |
| `flowRef` | string |

### NewMessage

| Field | Type |
| --- | --- |
| `message` | string |

### Node

| Field | Type |
| --- | --- |
| `id` | string |
| `kind` | string |
| `stage` | string |
| `label` | string |
| `service` | string |
| `logger` | string |
| `status` | string |
| `exceptionType` | string |
| `eventCount` | integer (int32) |
| `firstTs` | string |
| `lastTs` | string |
| `evidenceIds` | array of string |
| `anomalyIds` | array of integer (int64) |
| `minConfidence` | number |
| `isFirstFailure` | boolean |

### Overview

| Field | Type |
| --- | --- |
| `from` | string |
| `to` | string |
| `flowId` | string |
| `timezone` | string |
| `flows` | integer (int64) |
| `byStatus` | map of integer (int64) |
| `byStage` | map of map of integer (int64) |
| `anomaliesByRule` | array of RuleCount |
| `byService` | array of ServiceErrors |
| `topFailingComponents` | array of ComponentFailures |
| `perDay` | array of Day |

### PageResponseAuditEvent

| Field | Type |
| --- | --- |
| `items` | array of AuditEvent |
| `page` | integer (int32) |
| `size` | integer (int32) |
| `totalElements` | integer (int64) |
| `totalPages` | integer (int32) |
| `totalIsLowerBound` | boolean |

### PageResponseEvidenceLine

| Field | Type |
| --- | --- |
| `items` | array of EvidenceLine |
| `page` | integer (int32) |
| `size` | integer (int32) |
| `totalElements` | integer (int64) |
| `totalPages` | integer (int32) |
| `totalIsLowerBound` | boolean |

### PageResponseFlowSummary

| Field | Type |
| --- | --- |
| `items` | array of FlowSummary |
| `page` | integer (int32) |
| `size` | integer (int32) |
| `totalElements` | integer (int64) |
| `totalPages` | integer (int32) |
| `totalIsLowerBound` | boolean |

### PageResponseItem

| Field | Type |
| --- | --- |
| `items` | array of Item |
| `page` | integer (int32) |
| `size` | integer (int32) |
| `totalElements` | integer (int64) |
| `totalPages` | integer (int32) |
| `totalIsLowerBound` | boolean |

### PageResponseLogLine

| Field | Type |
| --- | --- |
| `items` | array of LogLine |
| `page` | integer (int32) |
| `size` | integer (int32) |
| `totalElements` | integer (int64) |
| `totalPages` | integer (int32) |
| `totalIsLowerBound` | boolean |

### PermissionDenial

| Field | Type |
| --- | --- |
| `ts` | string (date-time) |
| `permission` | string |
| `resource` | string |

### RequestBodyDto

| Field | Type |
| --- | --- |
| `regenerate` | boolean |

### RequestLookup

| Field | Type |
| --- | --- |
| `requestId` | string |
| `flowRef` | string |
| `flowRefs` | array of string |
| `lines` | array of LogLine |

### Review

| Field | Type |
| --- | --- |
| `actor` | string |
| `fromStatus` | string |
| `toStatus` | string |
| `note` | string |
| `at` | string (date-time) |

### ReviewRequest

| Field | Type |
| --- | --- |
| `status` | string |
| `note` | string |
| `version` | integer (int32) |

### RuleCount

| Field | Type |
| --- | --- |
| `ruleId` | string |
| `severity` | string |
| `total` | integer (int64) |
| `open` | integer (int64) |

### ServiceErrors

| Field | Type |
| --- | --- |
| `service` | string |
| `errors` | integer (int64) |

### Session

| Field | Type |
| --- | --- |
| `sessionId` | string |
| `loginAt` | string (date-time) |
| `logoutAt` | string (date-time) |
| `endReason` | string |
| `clientIp` | string |
| `mfa` | string |
| `failedAttemptsBefore` | integer (int32) |

### Stage

| Field | Type |
| --- | --- |
| `stage` | string |
| `order` | integer (int32) |
| `status` | string |
| `userId` | string |
| `startedAt` | string (date-time) |
| `endedAt` | string (date-time) |
| `durationMs` | integer (int64) |
| `errorCount` | integer (int32) |

### StageView

| Field | Type |
| --- | --- |
| `name` | string |
| `order` | integer (int32) |
| `actorRole` | string |
| `canReject` | boolean |

### Summary

| Field | Type |
| --- | --- |
| `summaryId` | integer (int64) |
| `jobId` | string (uuid) |
| `flowRef` | string |
| `status` | string |
| `output` | JsonNode |
| `provider` | string |
| `model` | string |
| `promptVersion` | string |
| `contextHash` | string |
| `createdAt` | string (date-time) |
| `completedAt` | string (date-time) |
| `errorCode` | string |
| `inputTokens` | integer (int32) |
| `outputTokens` | integer (int32) |
| `evidence` | map of string |

### ToolCall

| Field | Type |
| --- | --- |
| `name` | string |
| `args` | string |
| `resultCount` | integer (int32) |
| `durationMs` | integer (int64) |
| `refused` | boolean |

### Trace

| Field | Type |
| --- | --- |
| `flowRef` | string |
| `flowId` | string |
| `flowVersion` | integer (int32) |
| `overallStatus` | string |
| `actors` | map of string |
| `requester` | string |
| `approver` | string |
| `executor` | string |
| `amount` | number |
| `currency` | string |
| `counterpartyLast4` | string |
| `firstSeen` | string (date-time) |
| `lastSeen` | string (date-time) |
| `durationMs` | integer (int64) |
| `stages` | array of Stage |
| `firstFailure` | FirstFailure |
| `anomalies` | array of View |
| `observedRatio` | number |
| `excludedCount` | integer (int32) |
| `truncated` | boolean |
| `linkCount` | integer (int32) |
| `assembledAt` | string (date-time) |
| `assemblyVersion` | integer (int32) |

### TraceGraph

| Field | Type |
| --- | --- |
| `graph_version` | integer (int32) |
| `flowId` | string |
| `flowRef` | string |
| `overallStatus` | string |
| `lanes` | array of Lane |
| `nodes` | array of Node |
| `edges` | array of Edge |

### Usage

| Field | Type |
| --- | --- |
| `inputTokens` | integer (int32) |
| `outputTokens` | integer (int32) |

### UserActivity

| Field | Type |
| --- | --- |
| `userId` | string |
| `from` | string (date-time) |
| `to` | string (date-time) |
| `sessions` | array of Session |
| `failedSignIns` | array of FailedSignIn |
| `flows` | array of UserFlow |
| `permissionDenials` | array of PermissionDenial |
| `anomalies` | array of View |

### UserFlow

| Field | Type |
| --- | --- |
| `flowRef` | string |
| `flowId` | string |
| `role` | string |
| `overallStatus` | string |
| `ts` | string (date-time) |

### UserSummary

| Field | Type |
| --- | --- |
| `id` | string |
| `username` | string |
| `email` | string |
| `fullName` | string |
| `enabled` | boolean |
| `roles` | array of string |
| `createdAt` | string (date-time) |

### View

| Field | Type |
| --- | --- |
| `id` | integer (int64) |
| `ruleId` | string |
| `ruleName` | string |
| `severity` | string |
| `status` | string |
| `details` | map of object |
| `evidenceDocIds` | array of string |
| `version` | integer (int32) |
