import { Link } from 'react-router-dom';
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useAnomalies, useFlowDefinitions, useStats } from '@/api/hooks';
import type { AnomalyItem, Stats } from '@/api/schemas';
import { Badge, StatusBadge } from '@/shared/ui/Badge';
import { Card, CardHeader } from '@/shared/ui/Card';
import { DataState } from '@/shared/ui/DataState';
import { Table, Td, Th } from '@/shared/ui/Table';
import { TimeRangePicker } from '@/shared/ui/TimeRangePicker';
import { useRangeParams } from '@/shared/routing/useRange';
import { useSearchParams } from 'react-router-dom';
import { formatTime } from '@/shared/format/time';
import { useOptionalAuth } from '@/shared/auth/AuthContext';

const SEVERITY_TONE = { CRITICAL: 'red', HIGH: 'amber', MEDIUM: 'blue', LOW: 'slate' } as const;
const AXIS = { stroke: '#8B94A7', fontSize: 11 };
const CHART_GRID = '#252C3A';
const TOOLTIP_STYLE = { background: '#11151D', border: '1px solid #252C3A', color: '#E6EAF2' };

/**
 * Dashboard (section 12.2): flows per day by status, anomalies by rule, errors by service and the
 * top failing components for a flow definition and time range, plus recent CRITICAL and HIGH
 * anomalies. Every number comes from `/api/stats/overview` and `/api/anomalies` as given.
 */
export function DashboardPage() {
  const [range, setRange] = useRangeParams();
  const [params, setParams] = useSearchParams();
  const definitions = useFlowDefinitions();
  const flowId = params.get('flowId') ?? '';
  const stats = useStats({ ...range, flowId: flowId || undefined });
  const critical = useAnomalies({ ...range, severity: 'CRITICAL', status: 'NEW', size: 5 });
  const high = useAnomalies({ ...range, severity: 'HIGH', status: 'NEW', size: 5 });
  const user = useOptionalAuth()?.user;

  return (
    <div className="view-fade space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-ink-primary">Dashboard</h1>
          {user && (
            <p className="mt-1 text-xs text-ink-secondary">
              Signed in as <strong className="text-ink-primary">{user.username}</strong> ({user.roles.join(', ')})
            </p>
          )}
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-xs text-ink-secondary">
            Flow definition
            <select
              className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-2 py-1.5 text-sm text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
              value={flowId}
              onChange={(e) => {
                const next = new URLSearchParams(params);
                if (e.target.value) {
                  next.set('flowId', e.target.value);
                } else {
                  next.delete('flowId');
                }
                setParams(next);
              }}
            >
              <option value="">All</option>
              {definitions.data?.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.id}
                </option>
              ))}
            </select>
          </label>
          <TimeRangePicker value={range} onChange={setRange} />
        </div>
      </div>
      <DataState query={stats} loadingLabel="Counting flows…" isEmpty={(s) => s.flows === 0 && s.anomaliesByRule.length === 0}
                 emptyMessage="No assembled flows or anomalies in this range. Run make demo, or pick another range.">
        {(s) => <StatsView stats={s} />}
      </DataState>
      <Card>
        <CardHeader title="Recent CRITICAL and HIGH anomalies (not reviewed)" actions={<Link className="text-xs text-primary hover:underline" to="/anomalies">All anomalies</Link>} />
        <DataState query={critical} isEmpty={() => false}>
          {(c) => (
            <DataState query={high} isEmpty={(h) => c.items.length + h.items.length === 0} emptyMessage="No open CRITICAL or HIGH anomalies.">
              {(h) => <RecentAnomalies items={[...c.items, ...h.items]} />}
            </DataState>
          )}
        </DataState>
      </Card>
    </div>
  );
}

function StatsView({ stats }: { stats: Stats }) {
  return (
    <>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-5" aria-label="Flows by status">
        <Card className="px-4 py-3">
          <p className="text-xs text-ink-secondary">Flows</p>
          <p className="text-2xl font-semibold text-ink-primary">{stats.flows}</p>
        </Card>
        {Object.entries(stats.byStatus).map(([status, n]) => (
          <Card key={status} className="px-4 py-3">
            <StatusBadge status={status} />
            <p className="mt-1 text-2xl font-semibold text-ink-primary">{n}</p>
          </Card>
        ))}
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title={`Flows per day (${stats.timezone})`} />
          <div className="h-64 p-2">
            <ResponsiveContainer>
              <BarChart data={stats.perDay}>
                <CartesianGrid stroke={CHART_GRID} />
                <XAxis dataKey="day" {...AXIS} />
                <YAxis {...AXIS} allowDecimals={false} />
                <Tooltip contentStyle={TOOLTIP_STYLE} />
                <Legend />
                <Bar dataKey="completed" stackId="s" fill="#39A86B" name="Completed" />
                <Bar dataKey="failed" stackId="s" fill="#DC4B5A" name="Failed" />
                <Bar dataKey="rejected" stackId="s" fill="#9A72E6" name="Rejected" />
                <Bar dataKey="incomplete" stackId="s" fill="#D99A35" name="Incomplete" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </Card>
        <Card>
          <CardHeader title="Anomalies by rule" />
          <div className="h-64 p-2">
            <ResponsiveContainer>
              <BarChart data={stats.anomaliesByRule}>
                <CartesianGrid stroke={CHART_GRID} />
                <XAxis dataKey="ruleId" {...AXIS} />
                <YAxis {...AXIS} allowDecimals={false} />
                <Tooltip contentStyle={TOOLTIP_STYLE} />
                <Legend />
                <Bar dataKey="total" fill="#8B94A7" name="All" />
                <Bar dataKey="open" fill="#4C8DFF" name="Not reviewed" />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <ul className="flex flex-wrap gap-2 px-4 pb-3 text-xs" aria-label="Anomaly counts by rule">
            {stats.anomaliesByRule.map((r) => (
              <li key={r.ruleId}>
                <Link to={`/anomalies?ruleId=${r.ruleId}`} className="hover:underline">
                  <Badge tone={SEVERITY_TONE[r.severity]}>{r.ruleId}</Badge> {r.total}
                </Link>
              </li>
            ))}
          </ul>
        </Card>
        <Card>
          <CardHeader title="Errors by service" />
          <div className="h-56 p-2">
            <ResponsiveContainer>
              <BarChart data={stats.byService} layout="vertical">
                <CartesianGrid stroke={CHART_GRID} />
                <XAxis type="number" {...AXIS} allowDecimals={false} />
                <YAxis type="category" dataKey="service" width={120} {...AXIS} />
                <Tooltip contentStyle={TOOLTIP_STYLE} />
                <Bar dataKey="errors" fill="#DC4B5A" name="Error lines" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </Card>
        <Card>
          <CardHeader title="Top failing components (first failure)" />
          <Table aria-label="Top failing components">
            <thead>
              <tr>
                <Th>Component</Th>
                <Th className="text-right">Flows</Th>
              </tr>
            </thead>
            <tbody>
              {stats.topFailingComponents.map((c) => (
                <tr key={c.logger}>
                  <Td className="font-mono">{c.logger}</Td>
                  <Td className="text-right">{c.count}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      </div>
    </>
  );
}

function RecentAnomalies({ items }: { items: AnomalyItem[] }) {
  return (
    <Table aria-label="Recent anomalies">
      <tbody>
        {items.map((a) => (
          <tr key={a.id}>
            <Td>
              <Badge tone={SEVERITY_TONE[a.severity]}>{a.severity}</Badge>
            </Td>
            <Td>
              {a.ruleId} {a.ruleName}
            </Td>
            <Td>
              <Subject anomaly={a} />
            </Td>
            <Td className="whitespace-nowrap text-slate-400">{formatTime(a.windowStart ?? a.detectedAt)}</Td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

export function Subject({ anomaly }: { anomaly: AnomalyItem }) {
  if (anomaly.flowRef) {
    return <Link className="font-mono text-blue-300 hover:underline" to={`/traces/${anomaly.flowRef}`}>{anomaly.flowRef}</Link>;
  }
  if (anomaly.userId) {
    return <Link className="font-mono text-blue-300 hover:underline" to={`/users/${anomaly.userId}`}>{anomaly.userId}</Link>;
  }
  return <span className="font-mono">{anomaly.clientIp ?? '—'}</span>;
}
