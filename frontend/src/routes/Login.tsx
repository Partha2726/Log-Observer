import { useState } from 'react';
import type { FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/shared/auth/AuthContext';
import { Activity, Lock, User, AlertCircle } from 'lucide-react';

export function Login() {
  const { status, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const redirectTo = safeNext(new URLSearchParams(location.search).get('next'))
    ?? (location.state as { from?: { pathname: string } } | null)?.from?.pathname
    ?? '/dashboard';

  if (status === 'authenticated') {
    return <Navigate to={redirectTo} replace />;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(username, password);
      navigate(redirectTo, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="w-full max-w-md">
      <div className="rounded-md border border-[var(--border-hairline)] bg-[var(--surface-panel)] p-8 shadow-xl shadow-black/20">
        <div className="flex flex-col items-center mb-8">
          <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-sm border border-[var(--accent)] bg-[var(--surface-raised)] text-primary">
            <Activity className="h-6 w-6" />
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-ink-primary">Log in to Log Observer</h1>
          <p className="mt-1 text-center text-sm text-ink-secondary">
            Trace user flows across services and log formats
          </p>
        </div>

        {error && (
          <div
            role="alert"
            aria-live="assertive"
            className="mb-6 flex items-start space-x-2 rounded-sm border border-[var(--status-failed)] bg-[var(--surface-raised)] p-3 text-sm text-ink-primary"
          >
            <AlertCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-status-failed" />
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} noValidate className="space-y-4">
          <div>
            <label htmlFor="login-username" className="mb-1.5 block text-xs font-semibold uppercase text-ink-secondary">
              Username
            </label>
            <div className="relative">
              <User className="absolute left-3 top-3 h-4 w-4 text-ink-secondary" />
              <input
                id="login-username"
                name="username"
                type="text"
                autoComplete="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                required
                placeholder="e.g. admin.dev"
                className="w-full rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] py-2 pl-9 pr-3 text-sm text-ink-primary placeholder:text-ink-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
              />
            </div>
          </div>

          <div>
            <label htmlFor="login-password" className="mb-1.5 block text-xs font-semibold uppercase text-ink-secondary">
              Password
            </label>
            <div className="relative">
              <Lock className="absolute left-3 top-3 h-4 w-4 text-ink-secondary" />
              <input
                id="login-password"
                name="password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
                placeholder="••••••••"
                className="w-full rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] py-2 pl-9 pr-3 text-sm text-ink-primary placeholder:text-ink-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
              />
            </div>
          </div>

          <button
            type="submit"
            disabled={submitting}
            className="mt-2 flex w-full items-center justify-center space-x-2 rounded-sm bg-primary px-4 py-2.5 text-sm font-semibold text-[var(--surface-page)] transition-colors duration-150 hover:bg-[#6a9fff] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)] disabled:opacity-50"
          >
            {submitting ? 'Logging in…' : 'Log in'}
          </button>
        </form>

        <div className="mt-6 border-t border-[var(--border-hairline)] pt-6 text-center text-xs text-ink-secondary">
          Role-based access control
        </div>
      </div>
    </div>
  );
}

/** Only same-origin paths: "/traces/X" yes, "//evil.example" or "https://…" no. */
function safeNext(next: string | null): string | null {
  if (!next || !next.startsWith('/') || next.startsWith('//')) {
    return null;
  }
  return next;
}
