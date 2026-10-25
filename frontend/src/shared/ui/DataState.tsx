import type { ReactNode } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { AlertCircle, Inbox, Loader2, RotateCw } from 'lucide-react';
import { ApiRequestError } from '@/api/http';
import { Button } from '@/shared/ui/Button';

interface Props<T> {
  query: Pick<UseQueryResult<T, Error>, 'data' | 'error' | 'isPending' | 'isError' | 'refetch' | 'isFetching'>;
  /** True when the loaded data has nothing to show. */
  isEmpty?: (data: T) => boolean;
  emptyMessage?: ReactNode;
  loadingLabel?: string;
  children: (data: T) => ReactNode;
}

/**
 * The four states every data view defines (section 12.3): loading, empty, error with
 * Retry and the request ID, and success.
 */
export function DataState<T>({ query, isEmpty, emptyMessage = 'Nothing to show.', loadingLabel = 'Loading…', children }: Props<T>) {
  if (query.isPending) {
    return (
      <div role="status" className="flex items-center gap-2 p-6 text-sm text-ink-secondary">
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
        {loadingLabel}
      </div>
    );
  }
  if (query.isError) {
    return <ErrorPanel error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />;
  }
  const data = query.data as T;
  if (isEmpty?.(data)) {
    return (
      <div className="flex items-center gap-2 p-6 text-sm text-ink-secondary">
        <Inbox className="h-4 w-4" aria-hidden />
        {emptyMessage}
      </div>
    );
  }
  return <>{children(data)}</>;
}

export function ErrorPanel({ error, onRetry, retrying }: { error: Error | null; onRetry?: () => void; retrying?: boolean }) {
  const apiError = error instanceof ApiRequestError ? error : null;
  return (
    <div role="alert" className="m-4 rounded-sm border border-[var(--status-failed)] bg-[var(--surface-raised)] p-4 text-sm text-ink-primary">
      <div className="flex items-start gap-2">
        <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-status-failed" aria-hidden />
        <div className="flex-1">
          <p className="font-medium">{error?.message ?? 'Something went wrong.'}</p>
          {apiError?.code && <p className="mt-1 text-xs text-status-failed">Code: {apiError.code}</p>}
          {apiError?.requestId && <p className="mt-0.5 font-mono text-xs text-status-failed">Request ID: {apiError.requestId}</p>}
        </div>
        {onRetry && (
          <Button variant="secondary" onClick={onRetry} disabled={retrying}>
            <RotateCw className="h-3.5 w-3.5" aria-hidden />
            Retry
          </Button>
        )}
      </div>
    </div>
  );
}
