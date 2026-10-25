import { describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderPage } from '@/test/render';
import { AuthProvider } from '@/shared/auth/AuthContext';
import { setStoredToken } from '@/shared/auth/tokenStorage';
import { AnomaliesPage } from '@/features/anomalies/AnomaliesPage';
import anomalies from '@/test/contract/anomalies.json';

type Page = typeof anomalies;
const item = anomalies.items[0];

function open() {
  return renderPage(
    <AuthProvider>
      <AnomaliesPage />
    </AuthProvider>,
    { path: '/anomalies', url: '/anomalies' },
  );
}

describe('AnomaliesPage', () => {
  it('UI-002: loading, success with the default NEW filter, empty, and error with Retry', async () => {
    const queries: string[] = [];
    server.use(http.get('/api/anomalies', ({ request }) => {
      queries.push(new URL(request.url).search);
      return HttpResponse.json(anomalies);
    }));
    open();
    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(await screen.findByText(item.flowRef!)).toBeInTheDocument();
    expect(queries[0]).toContain('status=NEW');

    server.use(http.get('/api/anomalies', () => HttpResponse.json({ ...anomalies, items: [], totalElements: 0, totalPages: 0 } satisfies Page)));
    fireEvent.change(screen.getByLabelText('Rule'), { target: { value: 'R07' } });
    expect(await screen.findByText('No anomalies match these filters.')).toBeInTheDocument();

    server.use(http.get('/api/anomalies', () =>
      HttpResponse.json({ code: 'VALIDATION_ERROR', status: 400, message: 'from: must not be after to', requestId: 'req-1' }, { status: 400 })));
    fireEvent.change(screen.getByLabelText('Severity'), { target: { value: 'HIGH' } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('req-1');
  });

  it('UI-008: Confirm sends the note and the row version', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.get('/api/anomalies', () => HttpResponse.json(anomalies)),
      http.patch(`/api/anomalies/${item.id}`, async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({ ...item, status: 'CONFIRMED', version: item.version + 1, evidenceDocIds: ['d'], reviewedBy: 'devops.dev',
          reviewNote: 'Checked', reviewedAt: '2026-05-16T00:00:00.000Z', history: [] });
      }),
    );
    open();
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm' }));
    fireEvent.change(screen.getByLabelText('Review note'), { target: { value: 'Checked with the team' } });
    fireEvent.click(screen.getByRole('button', { name: /save confirmed/i }));
    await waitFor(() => expect(bodies).toEqual([{ status: 'CONFIRMED', note: 'Checked with the team', version: item.version }]));
  });

  it('UI-008: a 409 says someone else updated the row and reloads the list', async () => {
    let loads = 0;
    server.use(
      http.get('/api/anomalies', () => {
        loads++;
        return HttpResponse.json(anomalies);
      }),
      http.patch(`/api/anomalies/${item.id}`, () =>
        HttpResponse.json({ code: 'CONFLICT', status: 409, message: 'Anomaly was updated by someone else' }, { status: 409 })),
    );
    open();
    fireEvent.click(await screen.findByRole('button', { name: 'Dismiss' }));
    fireEvent.change(screen.getByLabelText('Review note'), { target: { value: 'Known test traffic' } });
    fireEvent.click(screen.getByRole('button', { name: /save dismissed/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Updated by someone else');
    await waitFor(() => expect(loads).toBeGreaterThan(1));
  });

  it('administrators can reopen a reviewed anomaly', async () => {
    setStoredToken('test-token');
    server.use(
      http.get('/api/auth/me', () => HttpResponse.json({ id: 'u1', username: 'admin.dev', email: 'a@example.test', fullName: 'Admin', roles: ['ADMINISTRATOR'] })),
      http.get('/api/anomalies', () => HttpResponse.json({ ...anomalies, items: [{ ...item, status: 'CONFIRMED' }] })),
    );
    open();
    expect(await screen.findByRole('button', { name: 'Reopen' })).toBeInTheDocument();
    localStorage.clear();
  });
});
