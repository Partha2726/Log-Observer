import { describe, expect, it } from 'vitest';
import type { z } from 'zod';
import {
  AnomalyPageSchema,
  StatsSchema,
  ApiErrorSchema,
  EvidencePageSchema,
  FlowDefinitionsSchema,
  FlowPageSchema,
  LogPageSchema,
  RequestLookupSchema,
  TraceGraphSchema,
  TraceSchema,
  UserActivitySchema,
} from '@/api/schemas';
import trace from '@/test/contract/trace.json';
import graph from '@/test/contract/graph.json';
import events from '@/test/contract/events.json';
import flows from '@/test/contract/flows.json';
import logs from '@/test/contract/logs.json';
import activity from '@/test/contract/activity.json';
import request from '@/test/contract/request.json';
import definitions from '@/test/contract/definitions.json';
import notFound from '@/test/contract/error-not-found.json';
import stats from '@/test/contract/stats.json';
import anomalies from '@/test/contract/anomalies.json';
import validation from '@/test/contract/error-validation.json';

/**
 * UI-007: the backend's real responses (written by the backend's ContractFixturesTest)
 * parse with the frontend's zod schemas.
 */
const cases: Array<[string, z.ZodTypeAny, unknown]> = [
  ['trace', TraceSchema, trace],
  ['graph', TraceGraphSchema, graph],
  ['events', EvidencePageSchema, events],
  ['flows', FlowPageSchema, flows],
  ['logs', LogPageSchema, logs],
  ['activity', UserActivitySchema, activity],
  ['request', RequestLookupSchema, request],
  ['definitions', FlowDefinitionsSchema, definitions],
  ['stats', StatsSchema, stats],
  ['anomalies', AnomalyPageSchema, anomalies],
  ['error-not-found', ApiErrorSchema, notFound],
  ['error-validation', ApiErrorSchema, validation],
];

describe('backend contract fixtures', () => {
  it.each(cases)('%s parses with its zod schema', (_name, schema, fixture) => {
    const result = schema.safeParse(fixture);
    if (!result.success) {
      throw new Error(JSON.stringify(result.error.issues.slice(0, 5), null, 2));
    }
    expect(result.success).toBe(true);
  });

  it('rejects a graph with another graph_version', () => {
    expect(TraceGraphSchema.safeParse({ ...(graph as object), graph_version: 2 }).success).toBe(false);
  });
});
