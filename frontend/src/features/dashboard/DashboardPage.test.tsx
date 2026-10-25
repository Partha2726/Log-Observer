import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderPage } from '@/test/render';
import { DashboardPage } from '@/features/dashboard/DashboardPage';
import stats from '@/test/contract/stats.json';
import anomalies from '@/test/contract/anomalies.json';
import definitions from '@/test/contract/definitions.json';

function handlers(statsBody: object = stats, status = 200) {
  server.use(
    http.get('/api/flows/definitions', () => HttpResponse.json(definitions)),
    http.get('/api/stats/overview', () => HttpResponse.json(statsBody, { status })),
    http.get('/api/anomalies', ({ request }) => {
      const severity = new URL(request.url).searchParams.get('severity');
      return HttpResponse.json(severity === 'HIGH' ? anomalies : { ...anomalies, items: [], totalElements: 0, totalPages: 0 });
    }),
  );
}

function open() {
  return renderPage(<DashboardPage />, { path: '/dashboard', url: '/dashboard' });
}

describe('DashboardPage (UI-002)', () => {
  it('loading, then the counts exactly as the backend gives them', async () => {
    handlers();
    open();
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0);
    const statuses = await screen.findByLabelText('Flows by status');
    expect(within(statuses).getByText(String(stats.flows))).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Top failing components' })).toHaveTextContent('c.a.b.l.LimitCheck');
    expect(screen.getByLabelText('Anomaly counts by rule')).toHaveTextContent('R05');
    expect(await screen.findByRole('table', { name: 'Recent anomalies' })).toHaveTextContent(anomalies.items[0].flowRef!);
  });

  it('empty range', async () => {
    handlers({ ...stats, flows: 0, byStatus: {}, byStage: {}, anomaliesByRule: [], byService: [], topFailingComponents: [], perDay: [] });
    open();
    expect(await screen.findByText(/No assembled flows or anomalies in this range/)).toBeInTheDocument();
  });

  it('error with the request ID and Retry', async () => {
    handlers({ code: 'VALIDATION_ERROR', status: 400, message: 'to: the range may span at most 31 days', requestId: 'req-9' }, 400);
    open();
    const alerts = await screen.findAllByRole('alert');
    expect(alerts[0]).toHaveTextContent('req-9');
    expect(within(alerts[0]).getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });
});
