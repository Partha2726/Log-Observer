import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { App } from '@/app/App';

// Stub the shared API client so these tests don't depend on a running
// backend. `get` resolves differently depending on the URL so both the
// health-check smoke test and the /api/auth/me identity check work.
const getMock = vi.fn();
vi.mock('@/shared/api/apiClient', () => ({
  apiClient: {
    get: (...args: unknown[]) => getMock(...args),
    post: vi.fn(),
  },
  onUnauthorized: () => () => {},
  normalizeApiError: (error: unknown) => ({ status: null, message: String(error) }),
}));

const TOKEN_KEY = 'ielop.auth.token';

describe('App', () => {
  beforeEach(() => {
    window.localStorage.clear();
    getMock.mockReset();
  });

  it('redirects an unauthenticated visitor to the login screen', async () => {
    getMock.mockResolvedValue({ data: { status: 'UP' } });

    render(<App />);

    expect(screen.getByText(/Trace user flows across services/i)).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: /Log in/i })).toBeInTheDocument();
  });

  it('renders the dashboard placeholder for an already-authenticated visitor', async () => {
    window.localStorage.setItem(TOKEN_KEY, 'a-fake-token');
    getMock.mockImplementation((url: string) => {
      if (url === '/api/auth/me') {
        return Promise.resolve({
          data: {
            id: 'user-1',
            username: 'jane.doe',
            email: 'jane.doe@example.com',
            fullName: 'Jane Doe',
            enabled: true,
            roles: ['VIEWER'],
            createdAt: new Date().toISOString(),
          },
        });
      }
      return Promise.resolve({ data: { status: 'UP' } });
    });

    render(<App />);

    expect(await screen.findByRole('heading', { name: /Dashboard/i })).toBeInTheDocument();
    expect(await screen.findByText(/jane\.doe/i)).toBeInTheDocument();
  });
});
