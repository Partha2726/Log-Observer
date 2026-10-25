import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { PropsWithChildren } from 'react';
import { apiClient, normalizeApiError, onUnauthorized } from '@/shared/api/apiClient';
import { clearStoredToken, getStoredToken, setStoredToken } from '@/shared/auth/tokenStorage';

/** Safe user shape mirroring the backend's `UserSummary` DTO. */
export interface AuthUser {
  id: string;
  username: string;
  email: string;
  fullName: string;
  enabled: boolean;
  roles: string[];
  createdAt: string;
}

interface LoginResponseBody {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: AuthUser;
}

type AuthStatus = 'checking' | 'authenticated' | 'anonymous';

interface AuthContextValue {
  status: AuthStatus;
  user: AuthUser | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

/**
 * Owns authentication state for the app: on mount, if a token is
 * already stored, it validates it against `GET /api/auth/me` (rather
 * than trusting it blindly) so a stale/expired token doesn't present a
 * false "logged in" state. Also listens for backend 401s (see
 * `apiClient.onUnauthorized`) so an access token that stops being valid
 * mid-session (e.g. it expires) clears local state instead of leaving
 * the UI in a broken "authenticated but every call fails" limbo.
 */
export function AuthProvider({ children }: PropsWithChildren) {
  const [status, setStatus] = useState<AuthStatus>('checking');
  const [user, setUser] = useState<AuthUser | null>(null);

  const logout = useCallback(() => {
    clearStoredToken();
    setUser(null);
    setStatus('anonymous');
  }, []);

  useEffect(() => {
    const unsubscribe = onUnauthorized(logout);
    return unsubscribe;
  }, [logout]);

  useEffect(() => {
    let cancelled = false;
    const token = getStoredToken();
    if (!token) {
      setStatus('anonymous');
      return;
    }
    apiClient
      .get<AuthUser>('/api/auth/me')
      .then((response) => {
        if (!cancelled) {
          setUser(response.data);
          setStatus('authenticated');
        }
      })
      .catch(() => {
        if (!cancelled) {
          clearStoredToken();
          setStatus('anonymous');
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    try {
      const response = await apiClient.post<LoginResponseBody>('/api/auth/login', { username, password });
      setStoredToken(response.data.accessToken);
      setUser(response.data.user);
      setStatus('authenticated');
    } catch (error) {
      throw new Error(normalizeApiError(error).message);
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, login, logout }),
    [status, user, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** The auth state when an AuthProvider is present, otherwise null (pages rendered alone in tests). */
export function useOptionalAuth(): AuthContextValue | null {
  return useContext(AuthContext) ?? null;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
