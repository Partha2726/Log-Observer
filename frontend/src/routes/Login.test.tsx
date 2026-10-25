import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '@/shared/auth/AuthContext';
import { Login } from '@/routes/Login';

const postMock = vi.fn();
const getMock = vi.fn();
vi.mock('@/shared/api/apiClient', () => ({
  apiClient: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
  },
  onUnauthorized: () => () => {},
  normalizeApiError: (error: unknown) => {
    const message =
      typeof error === 'object' && error !== null && 'response' in error
        ? // eslint-disable-next-line @typescript-eslint/no-explicit-any
          (error as any).response?.data?.message
        : undefined;
    return { status: null, message: message ?? 'Request failed' };
  },
}));

function renderLogin() {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/login']}>
        <Login />
      </MemoryRouter>
    </AuthProvider>,
  );
}

describe('Login', () => {
  beforeEach(() => {
    window.localStorage.clear();
    postMock.mockReset();
    getMock.mockReset();
    getMock.mockResolvedValue({ data: { status: 'UP' } });
  });

  it('submits credentials and stores the returned token on success', async () => {
    postMock.mockResolvedValue({
      data: {
        accessToken: 'issued-token',
        tokenType: 'Bearer',
        expiresInSeconds: 3600,
        user: {
          id: 'user-1',
          username: 'jane.doe',
          email: 'jane.doe@example.com',
          fullName: 'Jane Doe',
          enabled: true,
          roles: ['VIEWER'],
          createdAt: new Date().toISOString(),
        },
      },
    });

    const user = userEvent.setup();
    renderLogin();

    await user.type(screen.getByLabelText(/username/i), 'jane.doe');
    await user.type(screen.getByLabelText(/password/i), 'CorrectHorseBattery9!');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(postMock).toHaveBeenCalledWith('/api/auth/login', {
        username: 'jane.doe',
        password: 'CorrectHorseBattery9!',
      });
    });
    await waitFor(() => {
      expect(window.localStorage.getItem('ielop.auth.token')).toBe('issued-token');
    });
  });

  it('shows an error message and does not store a token on invalid credentials', async () => {
    postMock.mockRejectedValue({
      response: { status: 401, data: { message: 'Invalid credentials.' } },
    });

    const user = userEvent.setup();
    renderLogin();

    await user.type(screen.getByLabelText(/username/i), 'jane.doe');
    await user.type(screen.getByLabelText(/password/i), 'wrong-password');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/invalid credentials/i);
    expect(window.localStorage.getItem('ielop.auth.token')).toBeNull();
  });
});
