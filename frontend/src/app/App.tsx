import { useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { AppLayout } from '@/shared/layout/AppLayout';
import { AuthProvider } from '@/shared/auth/AuthContext';
import { ProtectedRoute } from '@/shared/routing/ProtectedRoute';
import { Login } from '@/routes/Login';
import { DashboardPage } from '@/features/dashboard/DashboardPage';
import { AnomaliesPage } from '@/features/anomalies/AnomaliesPage';
import { AdminPage } from '@/features/admin/AdminPage';
import { createQueryClient } from '@/app/queryClient';
import { SearchPage } from '@/features/search/SearchPage';
import { UserActivityPage } from '@/features/users/UserActivityPage';
import { TraceViewPage } from '@/features/traces/TraceViewPage';
import { LogSearchPage } from '@/features/logs/LogSearchPage';
import { RequestPage } from '@/features/requests/RequestPage';

function guarded(element: JSX.Element) {
  return <ProtectedRoute>{element}</ProtectedRoute>;
}

/**
 * Root application component. Pages live under src/features (spec section 21);
 * every one but login needs a signed-in user.
 */
export function App() {
  const [queryClient] = useState(createQueryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <AppLayout>
            <Routes>
              <Route path="/" element={<Navigate to="/dashboard" replace />} />
              <Route path="/login" element={<Login />} />
              <Route path="/dashboard" element={guarded(<DashboardPage />)} />
              <Route path="/anomalies" element={guarded(<AnomaliesPage />)} />
              <Route path="/admin" element={guarded(<AdminPage />)} />
              <Route path="/search" element={guarded(<SearchPage />)} />
              <Route path="/users/:userId" element={guarded(<UserActivityPage />)} />
              <Route path="/traces/:flowRef" element={guarded(<TraceViewPage />)} />
              <Route path="/requests/:requestId" element={guarded(<RequestPage />)} />
              <Route path="/logs" element={guarded(<LogSearchPage />)} />
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Routes>
          </AppLayout>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}
