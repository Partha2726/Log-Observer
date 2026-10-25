import type { PropsWithChildren } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '@/shared/auth/AuthContext';
import { 
  Activity,
  FileSearch,
  LogOut, 
  Search,
  User as UserIcon,
  Layers,
  Settings,
  ShieldAlert
} from 'lucide-react';

export function AppLayout({ children }: PropsWithChildren) {
  const { user, logout, status } = useAuth();
  const location = useLocation();

  const isAuth = status === 'authenticated';
  const isLoginPage = location.pathname === '/login';

  const navItems = [
    { label: 'Dashboard', path: '/dashboard', icon: Layers, active: location.pathname === '/dashboard', badge: undefined as string | undefined },
    { label: 'Search', path: '/search', icon: Search, active: ['/search', '/traces', '/users', '/requests'].some((p) => location.pathname.startsWith(p)), badge: undefined as string | undefined },
    { label: 'Logs', path: '/logs', icon: FileSearch, active: location.pathname.startsWith('/logs'), badge: undefined as string | undefined },
    { label: 'Anomalies', path: '/anomalies', icon: ShieldAlert, active: location.pathname.startsWith('/anomalies'), badge: undefined as string | undefined },
    // Role-gated pages are hidden for other roles (section 12.7); the backend enforces the same rule.
    ...(user?.roles.includes('ADMINISTRATOR')
      ? [{ label: 'Admin', path: '/admin', icon: Settings, active: location.pathname.startsWith('/admin'), badge: undefined as string | undefined }]
      : []),
  ];

  if (isLoginPage) {
    return (
      <div className="min-h-screen bg-[var(--surface-page)] text-ink-primary flex flex-col items-center justify-center p-4">
        {children}
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[var(--surface-page)] text-ink-primary flex flex-col antialiased">
      {/* Top Navigation Bar */}
      <header className="sticky top-0 z-50 flex h-14 items-center justify-between border-b border-[var(--border-hairline)] bg-[var(--surface-panel)] px-6">
        <div className="flex items-center space-x-3">
          <div className="flex h-8 w-8 items-center justify-center rounded-sm border border-[var(--accent)] bg-[var(--surface-raised)] text-primary">
            <Activity className="h-5 w-5" />
          </div>
          <div>
            <span className="text-base font-semibold tracking-tight text-ink-primary">
              Log Observer
            </span>
          </div>
        </div>

        {/* User Status / Actions */}
        <div className="flex items-center space-x-4">
          {isAuth && user ? (
            <div className="flex items-center space-x-3 rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-raised)] px-3 py-1.5 text-sm">
              <UserIcon className="h-4 w-4 text-ink-secondary" />
              <div className="flex flex-col text-left">
                <span className="leading-none font-medium text-ink-primary">{user.fullName || 'Authenticated User'}</span>
                <span className="mt-0.5 font-mono text-[10px] text-primary">
                  {user.roles.includes('ADMINISTRATOR') ? 'ADMINISTRATOR' : 'BACKEND_DEVOPS_ENGINEER'}
                </span>
              </div>
              <button
                type="button"
                onClick={logout}
                className="ml-2 rounded-sm p-1 text-ink-secondary transition-colors duration-150 hover:bg-[var(--surface-hover)] hover:text-status-failed focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
                title="Log out"
              >
                <LogOut className="h-4 w-4" />
              </button>
            </div>
          ) : (
            <Link
              to="/login"
              className="rounded-sm bg-primary px-3 py-1.5 text-sm font-semibold text-[var(--surface-page)] transition-colors duration-150 hover:bg-[#6a9fff] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
            >
              Log in
            </Link>
          )}
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar */}
        <aside className="hidden w-60 flex-col justify-between border-r border-[var(--border-hairline)] bg-[var(--surface-panel)] p-3 md:flex">
          <nav className="space-y-1">
            <div className="px-3 py-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-secondary">
              Workspace
            </div>
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <Link
                  key={item.label}
                  to={item.path}
                  className={`flex items-center justify-between rounded-sm px-3 py-2 text-sm font-medium transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)] ${
                    item.active
                      ? 'border border-[var(--accent)] bg-[var(--surface-raised)] text-primary'
                      : 'text-ink-secondary hover:bg-[var(--surface-hover)] hover:text-ink-primary'
                  }`}
                >
                  <div className="flex items-center space-x-3">
                    <Icon className={`h-4 w-4 ${item.active ? 'text-primary' : 'text-ink-secondary'}`} />
                    <span>{item.label}</span>
                  </div>
                  {item.badge && (
                    <span className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-raised)] px-1.5 py-0.5 font-mono text-[10px] text-ink-secondary">
                      {item.badge}
                    </span>
                  )}
                </Link>
              );
            })}
          </nav>

          <div className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-raised)] p-3 text-xs text-ink-secondary">
            <div className="flex items-center space-x-2 mb-1">
              <span className="h-2 w-2 rounded-full bg-status-ok"></span>
              <span className="font-medium text-ink-primary">Generated data only</span>
            </div>
            <p className="text-[11px] text-ink-secondary">Fictional Acme Cloud logs</p>
          </div>
        </aside>

        {/* Main Content Area */}
        <main className="flex-1 overflow-y-auto bg-[var(--surface-page)] p-5 md:p-6">
          <div className="max-w-7xl mx-auto">{children}</div>
        </main>
      </div>
    </div>
  );
}
