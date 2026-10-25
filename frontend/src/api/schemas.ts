import { z } from 'zod';

/**
 * zod schemas mirroring the backend contracts (spec sections 7.6 and 8.8, docs/API.md).
 * Every API response is parsed with one of these; a mismatch shows the error state
 * with the request ID instead of rendering half-understood data (section 12.6).
 */

const isoTime = z.iso.datetime({ offset: true });
const nullableTime = isoTime.nullable();

export const OverallStatus = z.enum(['COMPLETED', 'FAILED', 'REJECTED', 'INCOMPLETE']);
export const StageStatus = z.enum(['COMPLETED', 'FAILED', 'REJECTED', 'INCOMPLETE', 'NOT_REACHED']);
export const EvidenceClass = z.enum(['OBSERVED', 'CORRELATED']);

export const ApiErrorSchema = z.object({
  code: z.string(),
  message: z.string().nullable().optional(),
  timestamp: z.string().optional(),
  requestId: z.string().optional(),
  status: z.number(),
  error: z.string().optional(),
  path: z.string().optional(),
  fieldErrors: z.record(z.string(), z.string()).optional(),
});

export function pageOf<T extends z.ZodTypeAny>(item: T) {
  return z.object({
    items: z.array(item),
    page: z.number().int(),
    size: z.number().int(),
    totalElements: z.number().int(),
    totalPages: z.number().int(),
    totalIsLowerBound: z.boolean(),
  });
}

export const FlowSummarySchema = z.object({
  flowRef: z.string(),
  flowId: z.string().nullable(),
  overallStatus: OverallStatus.nullable(),
  actors: z.record(z.string(), z.string()),
  requester: z.string().nullable(),
  approver: z.string().nullable(),
  executor: z.string().nullable(),
  firstSeen: nullableTime,
  lastSeen: nullableTime,
  amount: z.number().nullable(),
  currency: z.string().nullable(),
  anomalyCount: z.number().int(),
  maxSeverity: z.string().nullable(),
  assembled: z.boolean(),
});
export const FlowPageSchema = pageOf(FlowSummarySchema);

export const LogLineSchema = z.object({
  docId: z.string(),
  ts: isoTime,
  service: z.string().nullable(),
  host: z.string().nullable(),
  stage: z.string().nullable(),
  sourceFile: z.string().nullable(),
  level: z.string().nullable(),
  logger: z.string().nullable(),
  thread: z.string().nullable(),
  requestId: z.string().nullable(),
  flowRefs: z.array(z.string()),
  userId: z.string().nullable(),
  eventAction: z.string().nullable(),
  httpStatus: z.number().int().nullable(),
  snippet: z.string().nullable(),
  highlight: z.string().nullable(),
});
export const LogPageSchema = pageOf(LogLineSchema);

export const UserActivitySchema = z.object({
  userId: z.string(),
  from: isoTime,
  to: isoTime,
  sessions: z.array(
    z.object({
      sessionId: z.string().nullable(),
      loginAt: isoTime,
      logoutAt: nullableTime,
      endReason: z.enum(['LOGOUT', 'EXPIRED', 'OPEN']),
      clientIp: z.string().nullable(),
      mfa: z.string().nullable(),
      failedAttemptsBefore: z.number().int(),
    }),
  ),
  failedSignIns: z.array(
    z.object({ ts: isoTime, clientIp: z.string().nullable(), reason: z.string().nullable(), eventAction: z.string().nullable() }),
  ),
  flows: z.array(
    z.object({
      flowRef: z.string(),
      flowId: z.string(),
      role: z.string().nullable(),
      overallStatus: OverallStatus.nullable(),
      ts: nullableTime,
    }),
  ),
  permissionDenials: z.array(z.object({ ts: isoTime, permission: z.string().nullable(), resource: z.string().nullable() })),
  anomalies: z.array(z.unknown()),
});

export const RequestLookupSchema = z.object({
  requestId: z.string(),
  flowRef: z.string().nullable(),
  flowRefs: z.array(z.string()),
  lines: z.array(LogLineSchema),
});

export const AnomalyViewSchema = z.object({
  id: z.number().int(),
  ruleId: z.string(),
  ruleName: z.string(),
  severity: z.string(),
  status: z.string(),
  details: z.record(z.string(), z.unknown()),
  evidenceDocIds: z.array(z.string()),
  version: z.number().int(),
});

export const TraceSchema = z.object({
  flowRef: z.string(),
  flowId: z.string(),
  flowVersion: z.number().int(),
  overallStatus: OverallStatus,
  actors: z.record(z.string(), z.string()),
  requester: z.string().nullable(),
  approver: z.string().nullable(),
  executor: z.string().nullable(),
  amount: z.number().nullable(),
  currency: z.string().nullable(),
  counterpartyLast4: z.string().nullable(),
  firstSeen: isoTime,
  lastSeen: isoTime,
  durationMs: z.number().int().nullable(),
  stages: z.array(
    z.object({
      stage: z.string(),
      order: z.number().int(),
      status: StageStatus,
      userId: z.string().nullable(),
      startedAt: nullableTime,
      endedAt: nullableTime,
      durationMs: z.number().int().nullable(),
      errorCount: z.number().int(),
    }),
  ),
  firstFailure: z
    .object({
      stage: z.string(),
      component: z.string().nullable(),
      exception: z.string().nullable(),
      httpStatus: z.number().int().nullable(),
      docId: z.string().nullable(),
    })
    .nullable(),
  anomalies: z.array(AnomalyViewSchema),
  observedRatio: z.number().nullable(),
  excludedCount: z.number().int(),
  truncated: z.boolean(),
  linkCount: z.number().int(),
  assembledAt: isoTime,
  assemblyVersion: z.number().int(),
});

export const EvidenceLineSchema = z.object({
  docId: z.string(),
  ts: isoTime,
  service: z.string().nullable(),
  host: z.string().nullable(),
  stage: z.string(),
  sourceFile: z.string().nullable(),
  level: z.string().nullable(),
  logger: z.string().nullable(),
  thread: z.string().nullable(),
  requestId: z.string().nullable(),
  evidenceClass: EvidenceClass,
  confidence: z.number(),
  linkReason: z.string(),
  message: z.string().nullable(),
  parsed: z.record(z.string(), z.unknown()),
  isStacktrace: z.boolean(),
  expired: z.boolean(),
});
export const EvidencePageSchema = pageOf(EvidenceLineSchema);

export const NodeKind = z.enum(['ACTOR', 'SERVICE', 'COMPONENT', 'EXTERNAL', 'PLACEHOLDER']);
export const NodeStatus = z.enum(['OK', 'WARN', 'FAILED', 'REJECTED', 'NOT_REACHED']);
export const EdgeKind = z.enum(['sequence', 'handoff', 'call', 'integration']);

export const GraphNodeSchema = z.object({
  id: z.string(),
  kind: NodeKind,
  stage: z.string(),
  label: z.string(),
  service: z.string().optional(),
  logger: z.string().optional(),
  status: NodeStatus,
  exceptionType: z.string().optional(),
  eventCount: z.number().int(),
  firstTs: isoTime.optional(),
  lastTs: isoTime.optional(),
  evidenceIds: z.array(z.string()),
  anomalyIds: z.array(z.number()),
  minConfidence: z.number().optional(),
  isFirstFailure: z.boolean(),
});

export const TraceGraphSchema = z.object({
  graph_version: z.literal(1),
  flowId: z.string(),
  flowRef: z.string(),
  overallStatus: OverallStatus,
  lanes: z.array(
    z.object({
      stage: z.string(),
      status: StageStatus,
      userId: z.string().nullable(),
      startedAt: nullableTime,
      endedAt: nullableTime,
      durationMs: z.number().int().nullable(),
    }),
  ),
  nodes: z.array(GraphNodeSchema),
  edges: z.array(
    z.object({
      id: z.string(),
      source: z.string(),
      target: z.string(),
      kind: EdgeKind,
      label: z.string().nullable(),
      evidenceIds: z.array(z.string()),
    }),
  ),
});

export const FlowDefinitionSchema = z.object({
  id: z.string(),
  version: z.number().int(),
  refPattern: z.string(),
  roles: z.array(z.string()),
  stages: z.array(z.object({ name: z.string(), order: z.number().int(), actorRole: z.string().nullable(), canReject: z.boolean() })),
  hasBusinessFields: z.boolean(),
});
export const FlowDefinitionsSchema = z.array(FlowDefinitionSchema);

// ---------------------------------------------------------------- anomalies and stats (section 8.8)

export const Severity = z.enum(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']);
export const AnomalyStatus = z.enum(['NEW', 'CONFIRMED', 'DISMISSED']);

export const AnomalyItemSchema = z.object({
  id: z.number().int(),
  ruleId: z.string(),
  ruleName: z.string(),
  severity: Severity,
  status: AnomalyStatus,
  flowRef: z.string().nullable(),
  userId: z.string().nullable(),
  clientIp: z.string().nullable(),
  detectedAt: isoTime,
  windowStart: nullableTime,
  windowEnd: nullableTime,
  details: z.record(z.string(), z.unknown()),
  evidenceCount: z.number().int(),
  version: z.number().int(),
});
export const AnomalyPageSchema = pageOf(AnomalyItemSchema);

export const AnomalyDetailSchema = AnomalyItemSchema.omit({ evidenceCount: true }).extend({
  evidenceDocIds: z.array(z.string()),
  reviewedBy: z.string().nullable(),
  reviewNote: z.string().nullable(),
  reviewedAt: nullableTime,
  history: z.array(z.object({ actor: z.string(), fromStatus: z.string().nullable(), toStatus: z.string().nullable(), note: z.string().nullable(), at: isoTime })),
});

export const StatsSchema = z.object({
  from: z.string(),
  to: z.string(),
  flowId: z.string().nullable(),
  timezone: z.string(),
  flows: z.number().int(),
  byStatus: z.record(z.string(), z.number().int()),
  byStage: z.record(z.string(), z.record(z.string(), z.number().int())),
  anomaliesByRule: z.array(z.object({ ruleId: z.string(), severity: Severity, total: z.number().int(), open: z.number().int() })),
  byService: z.array(z.object({ service: z.string(), errors: z.number().int() })),
  topFailingComponents: z.array(z.object({ logger: z.string(), count: z.number().int() })),
  perDay: z.array(z.object({ day: z.string(), completed: z.number().int(), failed: z.number().int(), rejected: z.number().int(), incomplete: z.number().int() })),
});

// ---------------------------------------------------------------- administration (sections 8.8 and 17)

export const AdminHealthSchema = z.object({
  at: isoTime,
  components: z.record(z.string(), z.object({ status: z.string(), details: z.record(z.string(), z.unknown()) })),
  jobs: z.record(z.string(), z.record(z.string(), z.number().int())),
  ingest: z.object({
    events: z.number().int(),
    parseFailures: z.number().int(),
    parseFailureRatio: z.number(),
    latestEventAt: nullableTime,
    latestIngestedAt: nullableTime,
  }).nullable(),
  ingestError: z.string().nullable(),
  findingsLast24h: z.record(z.string(), z.number().int()),
  summariesLast24h: z.record(z.string(), z.number().int()),
  alerts: z.array(z.string()),
});

export const AuditEventSchema = z.object({
  id: z.number().int(),
  occurredAt: isoTime,
  actor: z.string(),
  action: z.string(),
  targetType: z.string().nullable(),
  targetId: z.string().nullable(),
  requestId: z.string().nullable(),
  metadata: z.record(z.string(), z.unknown()),
});
export const AuditPageSchema = pageOf(AuditEventSchema);
export const JobAcceptedSchema = z.object({ jobId: z.string() });

// ---------------------------------------------------------------- AI (sections 8.8 and 11)

const cited = z.array(z.string());

/** The model's summary after server-side validation (prompt sum-1, section 11.4). */
export const SummaryOutputSchema = z.object({
  headline: z.string(),
  status: OverallStatus,
  whatHappened: z.array(z.object({ stage: z.string(), text: z.string(), evidenceIds: cited })),
  whereItFailed: z.object({ stage: z.string(), component: z.string(), text: z.string(), evidenceIds: cited }).nullable(),
  anomalyExplanations: z.array(z.object({ ruleId: z.string(), text: z.string(), evidenceIds: cited })),
  likelyCause: z.object({ text: z.string(), confidence: z.enum(['LOW', 'MEDIUM', 'HIGH']), evidenceIds: cited }).nullable(),
  suggestedChecks: z.array(z.string()),
  uncertainty: z.string(),
});

export const SummaryStatus = z.enum(['REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED']);

export const AiSummarySchema = z.object({
  summaryId: z.number().int(),
  jobId: z.string().nullable(),
  flowRef: z.string(),
  status: SummaryStatus,
  output: SummaryOutputSchema.nullable(),
  provider: z.string(),
  model: z.string(),
  promptVersion: z.string(),
  contextHash: z.string(),
  createdAt: isoTime,
  completedAt: nullableTime,
  errorCode: z.string().nullable(),
  inputTokens: z.number().int().nullable(),
  outputTokens: z.number().int().nullable(),
  evidence: z.record(z.string(), z.string()),
});

export const SummaryQueuedSchema = z.object({ jobId: z.string(), summaryId: z.number().int() });

export const JobStatusSchema = z.object({
  id: z.string(),
  type: z.string(),
  status: z.enum(['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED']),
  attemptCount: z.number().int(),
  maxAttempts: z.number().int(),
  errorCode: z.string().nullable(),
  createdAt: nullableTime,
  startedAt: nullableTime,
  completedAt: nullableTime,
});

export const CitationSchema = z.object({ ref: z.string(), docId: z.string(), stage: z.string().nullable(), ts: z.string().nullable() });
export const ToolCallSchema = z.object({
  name: z.string(),
  args: z.string(),
  resultCount: z.number().int(),
  durationMs: z.number().int(),
  refused: z.boolean(),
});

export const ChatAnswerSchema = z.object({
  answer: z.string(),
  citations: z.array(CitationSchema),
  toolCalls: z.array(ToolCallSchema),
  usage: z.object({ inputTokens: z.number().int(), outputTokens: z.number().int() }),
});

export const ConversationCreatedSchema = z.object({ conversationId: z.string() });

export type FlowSummary = z.infer<typeof FlowSummarySchema>;
export type FlowPage = z.infer<typeof FlowPageSchema>;
export type LogLine = z.infer<typeof LogLineSchema>;
export type LogPage = z.infer<typeof LogPageSchema>;
export type UserActivity = z.infer<typeof UserActivitySchema>;
export type RequestLookup = z.infer<typeof RequestLookupSchema>;
export type Trace = z.infer<typeof TraceSchema>;
export type EvidenceLine = z.infer<typeof EvidenceLineSchema>;
export type EvidencePage = z.infer<typeof EvidencePageSchema>;
export type TraceGraph = z.infer<typeof TraceGraphSchema>;
export type GraphNode = z.infer<typeof GraphNodeSchema>;
export type FlowDefinition = z.infer<typeof FlowDefinitionSchema>;
export type AnomalyView = z.infer<typeof AnomalyViewSchema>;
export type AnomalyItem = z.infer<typeof AnomalyItemSchema>;
export type AnomalyDetail = z.infer<typeof AnomalyDetailSchema>;
export type Stats = z.infer<typeof StatsSchema>;
export type AdminHealth = z.infer<typeof AdminHealthSchema>;
export type SummaryOutput = z.infer<typeof SummaryOutputSchema>;
export type AiSummary = z.infer<typeof AiSummarySchema>;
export type JobStatus = z.infer<typeof JobStatusSchema>;
export type Citation = z.infer<typeof CitationSchema>;
export type ToolCall = z.infer<typeof ToolCallSchema>;
export type ChatAnswer = z.infer<typeof ChatAnswerSchema>;
