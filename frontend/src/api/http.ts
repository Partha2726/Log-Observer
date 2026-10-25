import type { z } from 'zod';
import { apiClient, normalizeApiError } from '@/shared/api/apiClient';

/** An API call that failed or returned data that does not match its contract. */
export class ApiRequestError extends Error {
  readonly status: number | null;
  readonly code: string | null;
  readonly requestId: string | null;
  readonly fieldErrors?: Record<string, string>;

  constructor(message: string, opts: { status: number | null; code: string | null; requestId: string | null; fieldErrors?: Record<string, string> }) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = opts.status;
    this.code = opts.code;
    this.requestId = opts.requestId;
    this.fieldErrors = opts.fieldErrors;
  }
}

type Params = Record<string, string | number | boolean | null | undefined>;

function clean(params?: Params): Record<string, string | number | boolean> | undefined {
  if (!params) {
    return undefined;
  }
  const out: Record<string, string | number | boolean> = {};
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      out[key] = value;
    }
  }
  return out;
}

/** GET a JSON resource and parse it with its zod schema (section 12.6). */
export async function getParsed<S extends z.ZodTypeAny>(url: string, schema: S, params?: Params): Promise<z.infer<S>> {
  let response;
  try {
    response = await apiClient.get(url, { params: clean(params) });
  } catch (error) {
    const n = normalizeApiError(error);
    throw new ApiRequestError(n.message, { status: n.status, code: n.code, requestId: n.requestId, fieldErrors: n.fieldErrors });
  }
  const parsed = schema.safeParse(response.data);
  if (!parsed.success) {
    const headers = (response.headers ?? {}) as Record<string, string | undefined>;
    throw new ApiRequestError('The server returned data in an unexpected shape.', {
      status: response.status ?? null,
      code: 'CONTRACT_MISMATCH',
      requestId: headers['x-request-id'] ?? null,
    });
  }
  return parsed.data;
}

/** POST a JSON body and parse the response with its zod schema; returns the HTTP status too (200 vs 202). */
export async function postParsed<S extends z.ZodTypeAny>(url: string, schema: S, body?: unknown): Promise<{ status: number; data: z.infer<S> }> {
  let response;
  try {
    response = await apiClient.post(url, body ?? {});
  } catch (error) {
    const n = normalizeApiError(error);
    throw new ApiRequestError(n.message, { status: n.status, code: n.code, requestId: n.requestId, fieldErrors: n.fieldErrors });
  }
  const parsed = schema.safeParse(response.data);
  if (!parsed.success) {
    const headers = (response.headers ?? {}) as Record<string, string | undefined>;
    throw new ApiRequestError('The server returned data in an unexpected shape.', {
      status: response.status ?? null,
      code: 'CONTRACT_MISMATCH',
      requestId: headers['x-request-id'] ?? null,
    });
  }
  return { status: response.status, data: parsed.data };
}

/** PATCH a JSON body and parse the response with its zod schema. */
export async function patchParsed<S extends z.ZodTypeAny>(url: string, schema: S, body: unknown): Promise<z.infer<S>> {
  let response;
  try {
    response = await apiClient.patch(url, body);
  } catch (error) {
    const n = normalizeApiError(error);
    throw new ApiRequestError(n.message, { status: n.status, code: n.code, requestId: n.requestId, fieldErrors: n.fieldErrors });
  }
  const parsed = schema.safeParse(response.data);
  if (!parsed.success) {
    throw new ApiRequestError('The server returned data in an unexpected shape.', { status: response.status ?? null, code: 'CONTRACT_MISMATCH', requestId: null });
  }
  return parsed.data;
}
