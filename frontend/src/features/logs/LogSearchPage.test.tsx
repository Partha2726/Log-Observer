import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { server } from '@/test/server';
import { renderPage } from '@/test/render';
import { LogSearchPage } from '@/features/logs/LogSearchPage';
import definitions from '@/test/contract/definitions.json';
import logs from '@/test/contract/logs.json';

function open(url = '/logs?q=SQLTimeoutException&from=2026-05-14T00:00:00Z&to=2026-05-17T00:00:00Z') {
  return renderPage(<LogSearchPage />, { path: '/logs', url });
}

describe('LogSearchPage (UI-002)', () => {
  it('shows highlighted matches as text with links to the trace and request', async () => {
    let query = '';
    server.use(
      http.get('/api/flows/definitions', () => HttpResponse.json(definitions)),
      http.get('/api/search/logs', async ({ request }) => {
        query = new URL(request.url).search;
        await delay(20);
        return HttpResponse.json({
          ...logs,
          items: (logs as { items: object[] }).items.map((l) => ({ ...l, highlight: 'java.sql.<em>SQLTimeoutException</em>: <b>not html</b>' })),
        });
      }),
    );
    open();
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    const mark = await screen.findAllByText('SQLTimeoutException', { selector: 'mark' });
    expect(mark.length).toBeGreaterThan(0);
    expect(screen.getAllByText(/<b>not html<\/b>/).length).toBeGreaterThan(0);
    expect(query).toContain('q=SQLTimeoutException');
    const first = (logs as { items: Array<{ flowRefs: string[] }> }).items[0];
    const rows = screen.getAllByRole('row');
    expect(within(rows[1]).getByRole('link', { name: first.flowRefs[0] })).toBeInTheDocument();
  });

  it('shows the empty and error states', async () => {
    server.use(
      http.get('/api/flows/definitions', () => HttpResponse.json(definitions)),
      http.get('/api/search/logs', () => HttpResponse.json({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, totalIsLowerBound: false })),
    );
    const view = open();
    expect(await screen.findByText('No log lines match.')).toBeInTheDocument();
    view.unmount();

    server.use(http.get('/api/search/logs', () => HttpResponse.json({ code: 'ES_UNAVAILABLE', status: 503, message: 'Log store unavailable' }, { status: 503 })));
    open();
    expect(await screen.findByRole('alert')).toHaveTextContent('Log store unavailable');
  });
});
