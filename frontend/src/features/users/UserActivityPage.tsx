import { Link, useParams } from 'react-router-dom';
import { LogIn, LogOut, TimerOff } from 'lucide-react';
import { useUserActivity } from '@/api/hooks';
import type { UserActivity } from '@/api/schemas';
import { useRangeParams } from '@/shared/routing/useRange';
import { Badge, StatusBadge } from '@/shared/ui/Badge';
import { Card, CardHeader } from '@/shared/ui/Card';
import { DataState } from '@/shared/ui/DataState';
import { Table, Td, Th } from '@/shared/ui/Table';
import { TimeRangePicker } from '@/shared/ui/TimeRangePicker';
import { formatTime } from '@/shared/format/time';

const END_REASON = {
  LOGOUT: { label: 'signed out', Icon: LogOut, tone: 'green' as const },
  EXPIRED: { label: 'session expired', Icon: TimerOff, tone: 'amber' as const },
  OPEN: { label: 'still open', Icon: LogIn, tone: 'blue' as const },
};

/** User activity (sections 7.3 and 12.2): sessions from sign-in to sign-out or expiry, failures, denials and flows. */
export function UserActivityPage() {
  const { userId = '' } = useParams();
  const [range, setRange] = useRangeParams();
  const activity = useUserActivity(userId, range);
  const isEmpty = (a: UserActivity) =>
    a.sessions.length + a.flows.length + a.failedSignIns.length + a.permissionDenials.length === 0;

  return (
    <div className="view-fade space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-ink-primary">
            User <span className="font-mono">{userId}</span>
          </h1>
          <p className="mt-1 text-sm text-ink-secondary">Sessions, failed sign-ins, permission denials and the flows this user acted in.</p>
        </div>
        <TimeRangePicker value={range} onChange={setRange} />
      </div>

      <DataState query={activity} isEmpty={isEmpty} emptyMessage="No activity for this user in the selected range.">
        {(a) => (
          <div className="space-y-4">
            <Card>
              <CardHeader title={`Sessions (${a.sessions.length})`} />
              {a.sessions.length === 0 ? (
                <p className="p-4 text-sm text-slate-400">No sessions.</p>
              ) : (
                <ol className="divide-y divide-slate-800" aria-label="Session timeline">
                  {a.sessions.map((s, i) => {
                    const end = END_REASON[s.endReason];
                    return (
                      <li key={`${s.sessionId}-${i}`} className="flex flex-wrap items-center gap-3 px-4 py-2 text-sm">
                        <LogIn className="h-4 w-4 text-emerald-400" aria-hidden />
                        <span className="whitespace-nowrap text-xs" title={s.loginAt}>
                          {formatTime(s.loginAt)}
                        </span>
                        <span className="text-slate-500">→</span>
                        <span className="whitespace-nowrap text-xs" title={s.logoutAt ?? undefined}>
                          {formatTime(s.logoutAt)}
                        </span>
                        <Badge tone={end.tone}>
                          <end.Icon className="h-3 w-3" aria-hidden />
                          {end.label}
                        </Badge>
                        <span className="font-mono text-xs text-slate-400">{s.sessionId}</span>
                        {s.clientIp && <span className="font-mono text-xs text-slate-400">{s.clientIp}</span>}
                        {s.mfa && <Badge>MFA {s.mfa}</Badge>}
                        {s.failedAttemptsBefore > 0 && <Badge tone="red">{s.failedAttemptsBefore} failed attempts before</Badge>}
                      </li>
                    );
                  })}
                </ol>
              )}
            </Card>

            <Card>
              <CardHeader title={`Flows (${a.flows.length})`} />
              {a.flows.length === 0 ? (
                <p className="p-4 text-sm text-slate-400">No flows.</p>
              ) : (
                <Table>
                  <thead>
                    <tr>
                      <Th>Flow</Th>
                      <Th>Role</Th>
                      <Th>Status</Th>
                      <Th>First seen</Th>
                    </tr>
                  </thead>
                  <tbody>
                    {a.flows.map((f) => (
                      <tr key={f.flowRef}>
                        <Td>
                          <Link className="font-mono text-blue-300 hover:underline" to={`/traces/${encodeURIComponent(f.flowRef)}`}>
                            {f.flowRef}
                          </Link>
                        </Td>
                        <Td>{f.role ?? <span className="text-slate-500">not assembled yet</span>}</Td>
                        <Td>
                          <StatusBadge status={f.overallStatus} />
                        </Td>
                        <Td className="text-xs">{formatTime(f.ts)}</Td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
              )}
            </Card>

            <div className="grid gap-4 md:grid-cols-2">
              <Card>
                <CardHeader title={`Failed sign-ins (${a.failedSignIns.length})`} />
                {a.failedSignIns.length === 0 ? (
                  <p className="p-4 text-sm text-slate-400">None.</p>
                ) : (
                  <Table>
                    <tbody>
                      {a.failedSignIns.map((f, i) => (
                        <tr key={i}>
                          <Td className="text-xs">{formatTime(f.ts)}</Td>
                          <Td className="font-mono text-xs">{f.clientIp}</Td>
                          <Td className="text-xs">{f.reason ?? f.eventAction}</Td>
                        </tr>
                      ))}
                    </tbody>
                  </Table>
                )}
              </Card>
              <Card>
                <CardHeader title={`Permission denials (${a.permissionDenials.length})`} />
                {a.permissionDenials.length === 0 ? (
                  <p className="p-4 text-sm text-slate-400">None.</p>
                ) : (
                  <Table>
                    <tbody>
                      {a.permissionDenials.map((d, i) => (
                        <tr key={i}>
                          <Td className="text-xs">{formatTime(d.ts)}</Td>
                          <Td className="text-xs">{d.permission}</Td>
                          <Td className="font-mono text-xs">{d.resource}</Td>
                        </tr>
                      ))}
                    </tbody>
                  </Table>
                )}
              </Card>
            </div>
          </div>
        )}
      </DataState>
    </div>
  );
}
