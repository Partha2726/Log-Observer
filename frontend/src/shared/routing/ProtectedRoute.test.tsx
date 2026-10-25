import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { AuthProvider } from '@/shared/auth/AuthContext';
import { ProtectedRoute } from '@/shared/routing/ProtectedRoute';

const getMock = vi.fn();
vi.mock('@/shared/api/apiClient', () => ({
  apiClient: {
    get: (...args: unknown[]) => getMock(...args),
    post: vi.fn(),
  },
  onUnauthorized: () => () => {},
  normalizeApiError: (error: unknown) => ({ status: null, message: String(error) }),
}));

function renderProtected() {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route path="/login" element={<div>Login screen</div>} />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <div>Secret dashboard content</div>
              </ProtectedRoute>
            }
          />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    window.localStorage.clear();
    getMock.mockReset();
  });

  it('redirects to /login when there is no stored token', async () => {
    renderProtected();
    expect(await screen.findByText(/login screen/i)).toBeInTheDocument();
  });

  it('renders protected content when /api/auth/me confirms the token', async () => {
    window.localStorage.setItem('ielop.auth.token', 'a-fake-token');
    getMock.mockResolvedValue({
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

    renderProtected();
    expect(await screen.findByText(/secret dashboard content/i)).toBeInTheDocument();
  });

  it('redirects to /login when the stored token is rejected by the backend', async () => {
    window.localStorage.setItem('ielop.auth.token', 'a-stale-token');
    getMock.mockRejectedValue({ response: { status: 401 } });

    renderProtected();
    expect(await screen.findByText(/login screen/i)).toBeInTheDocument();
  });
});
