import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useAnomalies } from '@/api/hooks';
import { ApiRequestError, patchParsed } from '@/api/http';
import { AnomalyDetailSchema, type AnomalyItem } from '@/api/schemas';
import { useOptionalAuth } from '@/shared/auth/AuthContext';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card, CardHeader } from '@/shared/ui/Card';
import { DataState } from '@/shared/ui/DataState';
import { Pagination } from '@/shared/ui/Pagination';
import { Table, Td, Th } from '@/shared/ui/Table';
import { TimeRangePicker } from '@/shared/ui/TimeRangePicker';
import { useRangeParams } from '@/shared/routing/useRange';
import { formatTime } from '@/shared/format/time';
import { Subject } from '@/features/dashboard/DashboardPage';

const RULES = Array.from({ length: 15 }, (_, i) => `R${String(i + 1).padStart(2, '0')}`);
const SEVERITY_TONE = { CRITICAL: 'red', HIGH: 'amber', MEDIUM: 'blue', LOW: 'slate' } as const;
const STATUS_TONE = { NEW: 'blue', CONFIRMED: 'red', DISMISSED: 'slate' } as const;

/**
 * Anomalies (sections 10.2 and 12.2): a filterable list with Confirm and Dismiss and a note. A
 * review sends the row's `version`; a 409 means someone else changed it, and the list reloads.
 * Administrators may reopen reviewed rows.
 */
export function AnomaliesPage() {
  const [range, setRange] = useRangeParams();
  const [params, setParams] = useSearchParams();
  const filter = (key: string) => params.get(key) ?? '';
  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    next.delete('page');
    setParams(next);
  };
  const page = Number(params.get('page') ?? '0');
  const anomalies = useAnomalies({
    ...range,
    ruleId: filter('ruleId') || undefined,
    severity: filter('severity') || undefined,
    status: params.has('status') ? filter('status') || undefined : 'NEW',
    page,
    size: 25,
  });

  const select = (label: string, key: string, options: string[], fallback = '') => (
    <label className="flex flex-col gap-1 text-xs text-ink-secondary">
      {label}
      <select
        className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-2 py-1.5 text-sm text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
        value={params.has(key) ? filter(key) : fallback}
        onChange={(e) => set(key, e.target.value)}
        aria-label={label}
      >
        <option value="">All</option>
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </label>
  );

  return (
    <div className="view-fade space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight text-ink-primary">Anomalies</h1>
        <div className="flex flex-wrap items-end gap-3">
          {select('Rule', 'ruleId', RULES)}
          {select('Severity', 'severity', ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'])}
          {select('Status', 'status', ['NEW', 'CONFIRMED', 'DISMISSED'], 'NEW')}
          <TimeRangePicker value={range} onChange={setRange} />
        </div>
      </div>
      <Card>
        <CardHeader title="Findings" />
        <DataState query={anomalies} isEmpty={(p) => p.items.length === 0} emptyMessage="No anomalies match these filters.">
          {(p) => (
            <>
              <Table aria-label="Anomalies">
                <thead>
                  <tr>
                    <Th>Severity</Th>
                    <Th>Rule</Th>
                    <Th>Subject</Th>
                    <Th>Window</Th>
                    <Th>Evidence</Th>
                    <Th>Status</Th>
                    <Th>Review</Th>
                  </tr>
                </thead>
                <tbody>
                  {p.items.map((a) => (
                    <AnomalyRow key={a.id} anomaly={a} />
                  ))}
                </tbody>
              </Table>
              <Pagination page={p.page} totalPages={p.totalPages} totalElements={p.totalElements} lowerBound={p.totalIsLowerBound}
                          onPage={(n) => set('page', String(n))} />
            </>
          )}
        </DataState>
      </Card>
    </div>
  );
}

function AnomalyRow({ anomaly }: { anomaly: AnomalyItem }) {
  const user = useOptionalAuth()?.user;
  const admin = user?.roles.includes('ADMINISTRATOR') ?? false;
  const queryClient = useQueryClient();
  const [action, setAction] = useState<'CONFIRMED' | 'DISMISSED' | 'NEW' | null>(null);
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    if (!action) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await patchParsed(`/api/anomalies/${anomaly.id}`, AnomalyDetailSchema, { status: action, note: note.trim(), version: anomaly.version });
      setAction(null);
      setNote('');
      await queryClient.invalidateQueries({ queryKey: ['anomalies'] });
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 409) {
        setError('Updated by someone else; the list was reloaded. Check the row and review again.');
        setAction(null);
        await queryClient.invalidateQueries({ queryKey: ['anomalies'] });
      } else {
        setError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <tr data-anomaly-id={anomaly.id}>
      <Td>
        <Badge tone={SEVERITY_TONE[anomaly.severity]}>{anomaly.severity}</Badge>
      </Td>
      <Td>
        <span className="font-mono">{anomaly.ruleId}</span> {anomaly.ruleName}
      </Td>
      <Td>
        <Subject anomaly={anomaly} />
      </Td>
      <Td className="whitespace-nowrap text-slate-400">{formatTime(anomaly.windowStart ?? anomaly.detectedAt)}</Td>
      <Td>{anomaly.evidenceCount} lines</Td>
      <Td>
        <Badge tone={STATUS_TONE[anomaly.status]}>{anomaly.status}</Badge>
      </Td>
      <Td className="min-w-[16rem]">
        {action ? (
          <form
            className="flex flex-col gap-1"
            onSubmit={(e) => {
              e.preventDefault();
              void save();
            }}
          >
            <textarea
              className="rounded border border-slate-700 bg-slate-950 px-2 py-1 text-xs text-slate-100"
              rows={2}
              maxLength={1000}
              placeholder="Note (required)"
              aria-label="Review note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
            />
            <div className="flex gap-1">
              <Button type="submit" className="px-2 py-1 text-xs" disabled={saving || note.trim() === ''}>
                Save {action.toLowerCase()}
              </Button>
              <Button variant="ghost" className="px-2 py-1 text-xs" onClick={() => setAction(null)}>
                Cancel
              </Button>
            </div>
          </form>
        ) : anomaly.status === 'NEW' ? (
          <div className="flex gap-1">
            <Button variant="secondary" className="px-2 py-1 text-xs" onClick={() => setAction('CONFIRMED')}>
              Confirm
            </Button>
            <Button variant="ghost" className="px-2 py-1 text-xs" onClick={() => setAction('DISMISSED')}>
              Dismiss
            </Button>
          </div>
        ) : admin ? (
          <Button variant="ghost" className="px-2 py-1 text-xs" onClick={() => setAction('NEW')}>
            Reopen
          </Button>
        ) : null}
        {error && (
          <p role="alert" className="mt-1 text-xs text-amber-300">
            {error}
          </p>
        )}
      </Td>
    </tr>
  );
}
