import axios, { AxiosError } from 'axios';
import { getStoredToken } from '@/shared/auth/tokenStorage';

/**
 * Shared HTTP client foundation.
 *
 * All API calls should go through this client rather than importing
 * axios directly in components/features. This keeps a single place to
 * add cross-cutting concerns (auth token attachment, request IDs, retry policy, etc.).
 */
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10_000,
  headers: {
    'Content-Type': 'application/json',
  },
});

apiClient.interceptors.request.use((config) => {
  const token = getStoredToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

type UnauthorizedListener = () => void;
const unauthorizedListeners = new Set<UnauthorizedListener>();

/**
 * Subscribe to "the server rejected the current token as unauthorized"
 * events. Returns an unsubscribe function.
 */
export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener);
  return () => unauthorizedListeners.delete(listener);
}

apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      unauthorizedListeners.forEach((listener) => listener());
    }
    return Promise.reject(error);
  },
);

/**
 * Normalized API error structure matching the backend ApiError shape.
 */
export interface NormalizedApiError {
  code: string | null;
  status: number | null;
  error: string | null;
  message: string;
  requestId: string | null;
  fieldErrors?: Record<string, string>;
}

export function normalizeApiError(error: unknown): NormalizedApiError {
  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError<{
      code?: string;
      message?: string;
      error?: string;
      requestId?: string;
      fieldErrors?: Record<string, string>;
    }>;
    const data = axiosError.response?.data;
    return {
      code: data?.code ?? null,
      status: axiosError.response?.status ?? null,
      error: data?.error ?? null,
      message: data?.message ?? axiosError.message,
      requestId: data?.requestId ?? null,
      fieldErrors: data?.fieldErrors,
    };
  }
  return {
    code: null,
    status: null,
    error: null,
    message: error instanceof Error ? error.message : 'Unknown error',
    requestId: null,
  };
}
