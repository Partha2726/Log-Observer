import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '@/app/App';

const getMock = vi.fn();
const postMock = vi.fn();
vi.mock('@/shared/api/apiClient', () => ({
  apiClient: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
  },
  onUnauthorized: () => () => {},
  normalizeApiError: (error: unknown) => ({ status: 401, code: 'UNAUTHORIZED', requestId: null, message: String(error) }),
}));

const TOKEN_KEY = 'ielop.auth.token';
const USER = {
  id: 'user-1',
  username: 'devops.dev',
  email: 'devops.dev@example.test',
  fullName: 'Dev DevOps Engineer',
  enabled: true,
  roles: ['BACKEND_DEVOPS_ENGINEER'],
  createdAt: '2026-05-15T00:00:00.000Z',
};

/** UI-010: an expired token sends the user to login, and back to the page afterwards. */
describe('expired token', () => {
  beforeEach(() => {
    window.localStorage.clear();
    getMock.mockReset();
    postMock.mockReset();
  });

  it('redirects to login with next and returns after signing in', async () => {
    window.localStorage.setItem(TOKEN_KEY, 'an-expired-token');
    window.history.pushState({}, '', '/traces/REQ-20260515-000944');
    getMock.mockRejectedValue({ response: { status: 401 } });
    postMock.mockResolvedValue({ data: { accessToken: 'fresh', tokenType: 'Bearer', expiresInSeconds: 3600, user: USER } });

    render(<App />);

    expect(await screen.findByRole('heading', { name: /Log in/i })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/login');
    expect(new URLSearchParams(window.location.search).get('next')).toBe('/traces/REQ-20260515-000944');
    expect(window.localStorage.getItem(TOKEN_KEY)).toBeNull();

    await userEvent.type(screen.getByLabelText(/username/i), 'devops.dev');
    await userEvent.type(screen.getByLabelText(/password/i), 'DevPassword123!');
    await userEvent.click(screen.getByRole('button', { name: /log in|sign in/i }));

    await waitFor(() => expect(window.location.pathname).toBe('/traces/REQ-20260515-000944'));
    expect(screen.queryByRole('heading', { name: /Log in/i })).not.toBeInTheDocument();
  });
});
