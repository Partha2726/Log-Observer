import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { server } from '@/test/server';
import { renderPage } from '@/test/render';
import { UserActivityPage } from '@/features/users/UserActivityPage';
import activity from '@/test/contract/activity.json';

function open() {
  return renderPage(<UserActivityPage />, { path: '/users/:userId', url: '/users/usr-6490?from=2026-05-14T00:00:00Z&to=2026-05-17T00:00:00Z' });
}

const session = (endReason: string, logoutAt: string | null) => ({
  sessionId: `sess_${endReason.toLowerCase().padEnd(16, '0').slice(0, 16)}`,
  loginAt: '2026-05-15T08:13:13.643Z',
  logoutAt,
  endReason,
  clientIp: '10.20.4.17',
  mfa: 'totp',
  failedAttemptsBefore: endReason === 'OPEN' ? 3 : 0,
});

describe('UserActivityPage (UI-002)', () => {
  it('shows sessions ending in sign-out, expiry or still open, and the flows with roles', async () => {
    server.use(http.get('/api/users/:userId/activity', async () => {
      await delay(20);
      return HttpResponse.json({
        ...activity,
        sessions: [session('LOGOUT', '2026-05-15T08:15:00.000Z'), session('EXPIRED', '2026-05-15T08:40:00.000Z'), session('OPEN', null)],
      });
    }));
    open();
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    expect(await screen.findByText('signed out')).toBeInTheDocument();
    expect(screen.getByText('session expired')).toBeInTheDocument();
    expect(screen.getByText('still open')).toBeInTheDocument();
    expect(screen.getByText('3 failed attempts before')).toBeInTheDocument();
    const ref = (activity as { flows: Array<{ flowRef: string; role: string }> }).flows[0];
    expect(screen.getByRole('link', { name: ref.flowRef })).toBeInTheDocument();
    expect(screen.getByText(ref.role)).toBeInTheDocument();
  });

  it('shows the empty and error states', async () => {
    server.use(http.get('/api/users/:userId/activity', () =>
      HttpResponse.json({ ...activity, sessions: [], flows: [], failedSignIns: [], permissionDenials: [] })));
    const view = open();
    expect(await screen.findByText('No activity for this user in the selected range.')).toBeInTheDocument();
    view.unmount();

    server.use(http.get('/api/users/:userId/activity', () =>
      HttpResponse.json({ code: 'VALIDATION_ERROR', status: 400, message: 'to: the range may span at most 31 days', requestId: 'r-42' }, { status: 400 })));
    open();
    expect(await screen.findByRole('alert')).toHaveTextContent('31 days');
    expect(screen.getByRole('alert')).toHaveTextContent('r-42');
  });
});
