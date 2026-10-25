import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { server } from '@/test/server';
import { renderPage } from '@/test/render';
import { SearchPage } from '@/features/search/SearchPage';
import definitions from '@/test/contract/definitions.json';
import flows from '@/test/contract/flows.json';
import activity from '@/test/contract/activity.json';
import notFound from '@/test/contract/error-not-found.json';

const emptyPage = { items: [], page: 0, size: 25, totalElements: 0, totalPages: 0, totalIsLowerBound: false };

function withDefinitions() {
  server.use(http.get('/api/flows/definitions', () => HttpResponse.json(definitions)));
}

function open() {
  return renderPage(<SearchPage />, { path: '/search', url: '/search' });
}

describe('SearchPage', () => {
  it('UI-002: loading then success lists flows with status and actors', async () => {
    withDefinitions();
    server.use(http.get('/api/search/flows', async () => {
      await delay(30);
      return HttpResponse.json(flows);
    }));
    open();
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    const ref = (flows as { items: Array<{ flowRef: string }> }).items[0].flowRef;
    expect(await screen.findByRole('link', { name: ref })).toHaveAttribute('href', `/traces/${ref}`);
    expect(within(screen.getByRole('table')).getByText('FAILED')).toBeInTheDocument();
  });

  it('UI-002: empty and error states', async () => {
    withDefinitions();
    server.use(http.get('/api/search/flows', () => HttpResponse.json(emptyPage)));
    const view = open();
    expect(await screen.findByText('No flows in this range.')).toBeInTheDocument();
    view.unmount();

    server.use(http.get('/api/search/flows', () => HttpResponse.json({ ...notFound, status: 503, code: 'ES_UNAVAILABLE', message: 'Log store unavailable' }, { status: 503 })));
    open();
    expect(await screen.findByRole('alert')).toHaveTextContent('Log store unavailable');
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('UI-005: a flowRef goes straight to the trace view', async () => {
    withDefinitions();
    server.use(http.get('/api/search/flows', () => HttpResponse.json(emptyPage)));
    open();
    await userEvent.type(screen.getByLabelText('Search'), 'REQ-20260515-000944{Enter}');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/traces/REQ-20260515-000944'));
  });

  it('UI-005: a known user ID goes to the user page, checked with one call', async () => {
    withDefinitions();
    let calls = 0;
    server.use(
      http.get('/api/search/flows', () => HttpResponse.json(emptyPage)),
      http.get('/api/users/:userId/activity', () => {
        calls++;
        return HttpResponse.json(activity);
      }),
    );
    open();
    await userEvent.type(screen.getByLabelText('Search'), 'usr-6490{Enter}');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/users/usr-6490?from='));
    expect(calls).toBe(1);
  });

  it('UI-005: unknown words become a free-text log search', async () => {
    withDefinitions();
    server.use(
      http.get('/api/search/flows', () => HttpResponse.json(emptyPage)),
      http.get('/api/users/:userId/activity', () =>
        HttpResponse.json({ ...activity, sessions: [], flows: [], failedSignIns: [], permissionDenials: [] })),
    );
    open();
    await userEvent.type(screen.getByLabelText('Search'), 'timeout{Enter}');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/logs?q=timeout'));
  });
});
