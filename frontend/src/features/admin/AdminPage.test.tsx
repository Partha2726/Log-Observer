import { afterEach, describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderPage } from '@/test/render';
import { AuthProvider } from '@/shared/auth/AuthContext';
import { setStoredToken } from '@/shared/auth/tokenStorage';
import { AdminPage } from '@/features/admin/AdminPage';

const health = {
  at: '2026-10-12T10:00:00.000Z',
  components: { db: { status: 'UP', details: {} }, elasticsearch: { status: 'UP', details: {} },
    ai: { status: 'DISABLED', details: { reason: 'AI_API_KEY is not set', provider: 'openai', model: 'm' } } },
  jobs: { RULE_SCAN: { COMPLETED: 3 } },
  ingest: { events: 707026, parseFailures: 0, parseFailureRatio: 0, latestEventAt: '2026-10-11T10:00:00.000Z', latestIngestedAt: '2026-10-12T09:00:00.000Z' },
  ingestError: null,
  findingsLast24h: { R05: 4 },
  summariesLast24h: {},
  alerts: ['120 jobs are queued (alert above 100)'],
};

function signedInAs(roles: string[]) {
  setStoredToken('t');
  server.use(http.get('/api/auth/me', () => HttpResponse.json({ id: 'u', username: 'admin.dev', email: 'a@example.test', fullName: 'A', roles })));
}

function open() {
  return renderPage(<AuthProvider><AdminPage /></AuthProvider>, { path: '/admin', url: '/admin' });
}

describe('AdminPage', () => {
  afterEach(() => localStorage.clear());

  it('shows the health card, runs jobs and lists the audit log for an administrator', async () => {
    signedInAs(['ADMINISTRATOR']);
    server.use(
      http.get('/api/admin/health', () => HttpResponse.json(health)),
      http.get('/api/audit', () => HttpResponse.json({ items: [{ id: 1, occurredAt: '2026-10-12T09:00:00.000Z', actor: 'admin.dev',
        action: 'ANOMALY_REVIEWED', targetType: 'anomaly', targetId: '7', requestId: 'r', metadata: { toStatus: 'CONFIRMED' } }],
        page: 0, size: 25, totalElements: 1, totalPages: 1, totalIsLowerBound: false })),
      http.post('/api/admin/rule-scan', () => HttpResponse.json({ jobId: 'job-9' }, { status: 202 })),
      http.post('/api/admin/reassemble', () => HttpResponse.json({ code: 'CONFLICT', status: 409, message: 'A bulk re-assembly is already queued or running' }, { status: 409 })),
    );
    open();
    expect(await screen.findByText('120 jobs are queued (alert above 100)')).toBeInTheDocument();
    expect(screen.getByLabelText('Components')).toHaveTextContent('DISABLED');
    expect(screen.getByText('707,026')).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: 'Audit events' })).toHaveTextContent('ANOMALY_REVIEWED');

    fireEvent.click(screen.getByRole('button', { name: /run rule scan/i }));
    expect(await screen.findByText('Rule scan queued: job-9')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /re-assemble range/i }));
    expect(await screen.findByText(/Re-assembly queued failed: A bulk re-assembly is already queued or running \(CONFLICT\)/)).toBeInTheDocument();
  });

  it('other roles see a notice, not the controls', async () => {
    signedInAs(['BACKEND_DEVOPS_ENGINEER']);
    server.use(http.get('/api/admin/health', () => HttpResponse.json(health)), http.get('/api/audit', () => HttpResponse.json({})));
    open();
    expect(await screen.findByText('This page is for administrators.')).toBeInTheDocument();
  });
});
