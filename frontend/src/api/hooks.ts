import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { getParsed, type ApiRequestError } from '@/api/http';
import {
  AnomalyPageSchema,
  StatsSchema,
  EvidencePageSchema,
  FlowDefinitionsSchema,
  FlowPageSchema,
  LogPageSchema,
  RequestLookupSchema,
  TraceGraphSchema,
  TraceSchema,
  UserActivitySchema,
} from '@/api/schemas';

/** Server state for the pages; every response is parsed with its zod schema. */

export interface TimeRange {
  from?: string;
  to?: string;
}

export interface FlowSearchParams extends TimeRange {
  userId?: string;
  flowRef?: string;
  requestId?: string;
  flowId?: string;
  stage?: string;
  status?: string;
  hasAnomaly?: boolean;
  page?: number;
  size?: number;
}

export interface LogSearchParams extends TimeRange {
  q?: string;
  stage?: string;
  level?: string;
  service?: string;
  host?: string;
  clientIp?: string;
  page?: number;
  size?: number;
}

export interface EventParams {
  stage?: string;
  evidenceClass?: string;
  nodeId?: string;
  page?: number;
  size?: number;
}

export function useFlowDefinitions() {
  return useQuery<Awaited<ReturnType<typeof fetchDefinitions>>, ApiRequestError>({
    queryKey: ['flow-definitions'],
    queryFn: fetchDefinitions,
    staleTime: Infinity,
  });
}

function fetchDefinitions() {
  return getParsed('/api/flows/definitions', FlowDefinitionsSchema);
}

export function useFlowSearch(params: FlowSearchParams, enabled = true) {
  return useQuery({
    queryKey: ['flows', params],
    queryFn: () => getParsed('/api/search/flows', FlowPageSchema, { ...params }),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export function useLogSearch(params: LogSearchParams, enabled = true) {
  return useQuery({
    queryKey: ['logs', params],
    queryFn: () => getParsed('/api/search/logs', LogPageSchema, { ...params }),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export function useUserActivity(userId: string, range: TimeRange) {
  return useQuery({
    queryKey: ['user-activity', userId, range],
    queryFn: () => getParsed(`/api/users/${encodeURIComponent(userId)}/activity`, UserActivitySchema, { ...range }),
  });
}

export function useRequestLookup(requestId: string) {
  return useQuery({
    queryKey: ['request', requestId],
    queryFn: () => getParsed(`/api/requests/${encodeURIComponent(requestId)}`, RequestLookupSchema),
  });
}

export function useTrace(flowRef: string) {
  return useQuery({
    queryKey: ['trace', flowRef],
    queryFn: () => getParsed(`/api/traces/${encodeURIComponent(flowRef)}`, TraceSchema),
  });
}

/** The graph needs a stored trace, so it waits for the trace query. */
export function useTraceGraph(flowRef: string, enabled: boolean) {
  return useQuery({
    queryKey: ['trace-graph', flowRef],
    queryFn: () => getParsed(`/api/traces/${encodeURIComponent(flowRef)}/graph`, TraceGraphSchema),
    enabled,
  });
}

export function useTraceEvents(flowRef: string, params: EventParams, enabled: boolean) {
  return useQuery({
    queryKey: ['trace-events', flowRef, params],
    queryFn: () => getParsed(`/api/traces/${encodeURIComponent(flowRef)}/events`, EvidencePageSchema, { ...params }),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export interface AnomalyParams extends TimeRange {
  ruleId?: string;
  severity?: string;
  status?: string;
  userId?: string;
  flowRef?: string;
  clientIp?: string;
  page?: number;
  size?: number;
}

export function useAnomalies(params: AnomalyParams) {
  return useQuery({
    queryKey: ['anomalies', params],
    queryFn: () => getParsed('/api/anomalies', AnomalyPageSchema, { ...params }),
    placeholderData: keepPreviousData,
  });
}

export function useStats(params: TimeRange & { flowId?: string }) {
  return useQuery({
    queryKey: ['stats', params],
    queryFn: () => getParsed('/api/stats/overview', StatsSchema, { ...params }),
  });
}
