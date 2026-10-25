import type { PropsWithChildren } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '@/shared/auth/AuthContext';

/**
 * Route-guarding foundation to build
 * on. Redirects to `/login` if the user is not authenticated, preserving
 * the original location so login can send the user back to
 * where they were headed after login.
 *
 * While the initial `/api/auth/me` check is in flight, renders nothing
 * rather than flashing the login page or the protected content.
 */
export function ProtectedRoute({ children }: PropsWithChildren) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'checking') {
    return null;
  }

  if (status === 'anonymous') {
    // After login the user returns here (section 12.7), also after a 401 cleared an expired token.
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace state={{ from: location }} />;
  }

  return <>{children}</>;
}
