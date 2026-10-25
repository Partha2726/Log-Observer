import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Activity, Database, Play, ShieldCheck, Upload } from 'lucide-react';
import { ApiRequestError, getParsed, postParsed } from '@/api/http';
import { AdminHealthSchema, AuditPageSchema, JobAcceptedSchema, type AdminHealth } from '@/api/schemas';
import { apiClient } from '@/shared/api/apiClient';
import { useOptionalAuth } from '@/shared/auth/AuthContext';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card, CardHeader } from '@/shared/ui/Card';
import { DataState } from '@/shared/ui/DataState';
import { Pagination } from '@/shared/ui/Pagination';
import { Table, Td, Th } from '@/shared/ui/Table';
import { TimeRangePicker, presetRange, type Range } from '@/shared/ui/TimeRangePicker';
import { formatTime } from '@/shared/format/time';

/**
 * Administration (sections 8.8, 12.7 and 17), ADMINISTRATOR only: the health card, bulk re-assembly,
 * a manual rule scan, the approval-limit import and the audit log. The backend enforces the role
 * (SEC-002); other roles see a notice instead of the controls.
 */
export function AdminPage() {
  const user = useOptionalAuth()?.user;
  if (user && !user.roles.includes('ADMINISTRATOR')) {
    return <p className="text-sm text-ink-secondary">This page is for administrators.</p>;
  }
  return (
    <div className="view-fade space-y-4">
      <h1 className="text-2xl font-semibold tracking-tight text-ink-primary">Administration</h1>
      <HealthCard />
      <Actions />
      <AuditLog />
    </div>
  );
}

function HealthCard() {
  const health = useQuery({ queryKey: ['admin-health'], queryFn: () => getParsed('/api/admin/health', AdminHealthSchema), refetchInterval: 30_000 });
  return (
    <Card>
      <CardHeader title={<span className="inline-flex items-center gap-1.5"><Activity className="h-4 w-4" aria-hidden /> Health</span>} />
      <DataState query={health} loadingLabel="Checking health…">
        {(h) => <HealthView health={h} />}
      </DataState>
    </Card>
  );
}

const STATUS_TONE: Record<string, 'green' | 'red' | 'slate' | 'amber'> = { UP: 'green', DOWN: 'red', OUT_OF_SERVICE: 'red', DISABLED: 'slate' };

function HealthView({ health }: { health: AdminHealth }) {
  return (
    <div className="space-y-3 px-4 py-3 text-sm">
      {health.alerts.length > 0 ? (
        <ul role="alert" className="space-y-1 rounded-sm border border-[var(--status-warning)] bg-[var(--surface-raised)] px-3 py-2 text-status-warning">
          {health.alerts.map((a) => (
            <li key={a}>{a}</li>
          ))}
        </ul>
      ) : (
        <p className="flex items-center gap-1.5 text-status-ok"><ShieldCheck className="h-4 w-4" aria-hidden /> No alerts.</p>
      )}
      <div className="flex flex-wrap gap-3" aria-label="Components">
        {Object.entries(health.components).map(([name, c]) => (
          <div key={name} className="flex items-center gap-1.5">
            <span className="text-ink-primary">{name}</span>
            <Badge tone={STATUS_TONE[c.status] ?? 'amber'}>{c.status}</Badge>
            {typeof c.details.model === 'string' && c.details.model && <span className="text-xs text-ink-secondary">{String(c.details.model)}</span>}
            {typeof c.details.reason === 'string' && <span className="text-xs text-ink-secondary">{String(c.details.reason)}</span>}
          </div>
        ))}
      </div>
      <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-xs">
        <dt className="text-slate-400">Log lines</dt>
        <dd>{health.ingest ? health.ingest.events.toLocaleString() : health.ingestError}</dd>
        <dt className="text-slate-400">Parse failures</dt>
        <dd>{health.ingest ? `${health.ingest.parseFailures} (${(health.ingest.parseFailureRatio * 100).toFixed(3)}%)` : '—'}</dd>
        <dt className="text-slate-400">Latest event / ingested</dt>
        <dd>{health.ingest ? `${formatTime(health.ingest.latestEventAt)} / ${formatTime(health.ingest.latestIngestedAt)}` : '—'}</dd>
        <dt className="text-slate-400">Jobs</dt>
        <dd>
          {Object.entries(health.jobs).map(([type, byStatus]) => (
            <span key={type} className="mr-3">
              {type}: {Object.entries(byStatus).map(([s, n]) => `${s.toLowerCase()} ${n}`).join(', ')}
            </span>
          ))}
        </dd>
        <dt className="text-slate-400">Findings (24 h)</dt>
        <dd>{Object.entries(health.findingsLast24h).map(([r, n]) => `${r} ${n}`).join(', ') || 'none'}</dd>
        <dt className="text-slate-400">AI summaries (24 h)</dt>
        <dd>{Object.entries(health.summariesLast24h).map(([s, n]) => `${s.toLowerCase()} ${n}`).join(', ') || 'none'}</dd>
      </dl>
    </div>
  );
}

function Actions() {
  const [range, setRange] = useState<Range>(() => presetRange(7));
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const file = useRef<HTMLInputElement>(null);

  async function run(label: string, call: () => Promise<string>) {
    setMessage(null);
    try {
      setMessage({ text: `${label}: ${await call()}`, error: false });
    } catch (e) {
      const err = e instanceof ApiRequestError ? `${e.message}${e.code ? ` (${e.code})` : ''}` : String(e);
      setMessage({ text: `${label} failed: ${err}`, error: true });
    }
  }

  const query = `from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`;
  return (
    <Card>
      <CardHeader title={<span className="inline-flex items-center gap-1.5"><Database className="h-4 w-4" aria-hidden /> Jobs and data</span>} />
      <div className="space-y-3 px-4 py-3 text-sm">
        <TimeRangePicker value={range} onChange={setRange} />
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" onClick={() => void run('Re-assembly queued', async () =>
            (await postParsed(`/api/admin/reassemble?${query}`, JobAcceptedSchema)).data.jobId)}>
            <Play className="h-3.5 w-3.5" aria-hidden /> Re-assemble range
          </Button>
          <Button variant="secondary" onClick={() => void run('Rule scan queued', async () =>
            (await postParsed(`/api/admin/rule-scan?${query}`, JobAcceptedSchema)).data.jobId)}>
            <Play className="h-3.5 w-3.5" aria-hidden /> Run rule scan
          </Button>
          <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-md border border-slate-700 px-3 py-1.5 text-sm text-slate-100 hover:bg-slate-800">
            <Upload className="h-3.5 w-3.5" aria-hidden /> Import approval limits (users.jsonl)
            <input
              ref={file}
              type="file"
              accept=".jsonl,application/x-ndjson,application/json"
              className="hidden"
              aria-label="Approval limits file"
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) {
                  void run('Limits imported', async () => {
                    const form = new FormData();
                    form.append('file', f);
                    try {
                      const r = await apiClient.post('/api/admin/user-limits/import', form);
                      return `${r.data.imported} limits (${r.data.skipped} users without one)`;
                    } finally {
                      if (file.current) {
                        file.current.value = '';
                      }
                    }
                  });
                }
              }}
            />
          </label>
        </div>
        {message && (
          <p role={message.error ? 'alert' : 'status'} className={message.error ? 'text-red-300' : 'text-emerald-300'}>
            {message.text}
          </p>
        )}
      </div>
    </Card>
  );
}

function AuditLog() {
  const [page, setPage] = useState(0);
  const [action, setAction] = useState('');
  const audit = useQuery({
    queryKey: ['audit', page, action],
    queryFn: () => getParsed('/api/audit', AuditPageSchema, { page, size: 25, action: action || undefined }),
  });
  return (
    <Card>
      <CardHeader
        title="Audit log"
        actions={
          <input
            className="rounded-md border border-slate-700 bg-slate-950 px-2 py-1 text-xs text-slate-100"
            placeholder="Action, e.g. ANOMALY_REVIEWED"
            aria-label="Audit action"
            value={action}
            onChange={(e) => {
              setAction(e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, ''));
              setPage(0);
            }}
          />
        }
      />
      <DataState query={audit} isEmpty={(p) => p.items.length === 0} emptyMessage="No audit events.">
        {(p) => (
          <>
            <Table aria-label="Audit events">
              <thead>
                <tr>
                  <Th>Time</Th>
                  <Th>Actor</Th>
                  <Th>Action</Th>
                  <Th>Target</Th>
                  <Th>Details</Th>
                </tr>
              </thead>
              <tbody>
                {p.items.map((e) => (
                  <tr key={e.id}>
                    <Td className="whitespace-nowrap text-slate-400">{formatTime(e.occurredAt)}</Td>
                    <Td>{e.actor}</Td>
                    <Td className="font-mono text-xs">{e.action}</Td>
                    <Td className="font-mono text-xs">{[e.targetType, e.targetId].filter(Boolean).join(' ')}</Td>
                    <Td className="max-w-md truncate font-mono text-xs text-slate-400" title={JSON.stringify(e.metadata)}>
                      {JSON.stringify(e.metadata)}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pagination page={p.page} totalPages={p.totalPages} totalElements={p.totalElements} lowerBound={p.totalIsLowerBound} onPage={setPage} />
          </>
        )}
      </DataState>
    </Card>
  );
}
