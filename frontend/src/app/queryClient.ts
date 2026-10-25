import { QueryClient } from '@tanstack/react-query';
import { ApiRequestError } from '@/api/http';

/** Retry once on server or network errors, never on 4xx (the request itself is wrong). */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (failureCount, error) => {
          if (error instanceof ApiRequestError && error.status !== null && error.status < 500) {
            return false;
          }
          return failureCount < 1;
        },
        refetchOnWindowFocus: false,
        staleTime: 30_000,
      },
    },
  });
}
