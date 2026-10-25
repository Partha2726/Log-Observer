import { useQuery } from '@tanstack/react-query';
import { ApiRequestError, getParsed, postParsed } from '@/api/http';
import {
  AiSummarySchema,
  ChatAnswerSchema,
  ConversationCreatedSchema,
  JobStatusSchema,
  SummaryQueuedSchema,
  type AiSummary,
} from '@/api/schemas';

/** AI summary and assistant calls (sections 8.8 and 12.3). */

const TERMINAL_JOB = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

/** The latest summary of a trace; null when none was ever requested (404). Polls while it is in progress. */
export function useAiSummary(flowRef: string) {
  return useQuery<AiSummary | null, ApiRequestError>({
    queryKey: ['ai-summary', flowRef],
    queryFn: async () => {
      try {
        return await getParsed(`/api/traces/${encodeURIComponent(flowRef)}/ai-summary`, AiSummarySchema);
      } catch (error) {
        if (error instanceof ApiRequestError && error.status === 404) {
          return null;
        }
        throw error;
      }
    },
    refetchInterval: (query) => {
      const s = query.state.data?.status;
      return s === 'REQUESTED' || s === 'RUNNING' ? 2000 : false;
    },
  });
}

/** Polls a processing job every 2 s until it ends. */
export function useJob(jobId: string | null) {
  return useQuery({
    queryKey: ['job', jobId],
    queryFn: () => getParsed(`/api/jobs/${encodeURIComponent(jobId ?? '')}`, JobStatusSchema),
    enabled: jobId !== null,
    refetchInterval: (query) => (query.state.data && TERMINAL_JOB.has(query.state.data.status) ? false : 2000),
  });
}

export function isTerminalJob(status: string | undefined) {
  return status !== undefined && TERMINAL_JOB.has(status);
}

/** POST ai-summary: a cached summary (200) or the job producing one (202). */
export async function requestSummary(flowRef: string, regenerate: boolean) {
  const url = `/api/traces/${encodeURIComponent(flowRef)}/ai-summary`;
  const r = await postParsed(url, AiSummarySchema.or(SummaryQueuedSchema), { regenerate });
  return r.data;
}

export async function createConversation(flowRef: string | null) {
  const r = await postParsed('/api/assistant/conversations', ConversationCreatedSchema, flowRef ? { flowRef } : {});
  return r.data.conversationId;
}

export async function sendMessage(conversationId: string, message: string) {
  const r = await postParsed(`/api/assistant/conversations/${encodeURIComponent(conversationId)}/messages`, ChatAnswerSchema, { message });
  return r.data;
}
