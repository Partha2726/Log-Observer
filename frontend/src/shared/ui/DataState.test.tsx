import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DataState } from '@/shared/ui/DataState';
import { ApiRequestError } from '@/api/http';

type Query = Parameters<typeof DataState<string[]>>[0]['query'];

function query(partial: Partial<Query>): Query {
  return {
    data: undefined,
    error: null,
    isPending: false,
    isError: false,
    isFetching: false,
    refetch: vi.fn(),
    ...partial,
  } as Query;
}

/** UI-002 (shared component): loading, empty, error with Retry and request ID, success. */
describe('DataState', () => {
  it('shows a loading state', () => {
    render(<DataState query={query({ isPending: true })}>{() => <p>data</p>}</DataState>);
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
  });

  it('shows the empty state when the data has nothing to show', () => {
    render(
      <DataState query={query({ data: [] })} isEmpty={(d) => d.length === 0} emptyMessage="No flows found.">
        {() => <p>data</p>}
      </DataState>,
    );
    expect(screen.getByText('No flows found.')).toBeInTheDocument();
    expect(screen.queryByText('data')).not.toBeInTheDocument();
  });

  it('shows the error with its request ID and retries', async () => {
    const refetch = vi.fn();
    const error = new ApiRequestError('Log store unavailable', { status: 503, code: 'ES_UNAVAILABLE', requestId: 'req-123' });
    render(<DataState query={query({ isError: true, error, refetch })}>{() => <p>data</p>}</DataState>);

    expect(screen.getByRole('alert')).toHaveTextContent('Log store unavailable');
    expect(screen.getByRole('alert')).toHaveTextContent('req-123');
    expect(screen.getByRole('alert')).toHaveTextContent('ES_UNAVAILABLE');
    await userEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('renders the data on success', () => {
    render(
      <DataState query={query({ data: ['a', 'b'] })} isEmpty={(d) => d.length === 0}>
        {(d) => <p>{d.join(',')}</p>}
      </DataState>,
    );
    expect(screen.getByText('a,b')).toBeInTheDocument();
  });
});
