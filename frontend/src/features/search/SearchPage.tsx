import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Search as SearchIcon } from 'lucide-react';
import { useFlowDefinitions, useFlowSearch } from '@/api/hooks';
import { getParsed } from '@/api/http';
import { UserActivitySchema } from '@/api/schemas';
import { detect } from '@/features/search/detect';
import { useRangeParams } from '@/shared/routing/useRange';
import { Button } from '@/shared/ui/Button';
import { Card, CardHeader } from '@/shared/ui/Card';
import { DataState, ErrorPanel } from '@/shared/ui/DataState';
import { Pagination } from '@/shared/ui/Pagination';
import { StatusBadge } from '@/shared/ui/Badge';
import { Table, Td, Th } from '@/shared/ui/Table';
import { TimeRangePicker } from '@/shared/ui/TimeRangePicker';
import { formatTime } from '@/shared/format/time';

const STATUSES = ['COMPLETED', 'FAILED', 'REJECTED', 'INCOMPLETE'];

/**
 * Search page (section 12.2): the search box auto-detects what was typed (section 12.4)
 * and navigates; the filters list flows by definition, stage, status, anomaly and range.
 */
export function SearchPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [range, setRange] = useRangeParams();
  const definitions = useFlowDefinitions();
  const [input, setInput] = useState('');
  const [detecting, setDetecting] = useState(false);
  const [detectError, setDetectError] = useState<Error | null>(null);

  const flowId = params.get('flowId') ?? '';
  const stage = params.get('stage') ?? '';
  const status = params.get('status') ?? '';
  const hasAnomaly = params.get('hasAnomaly') ?? '';
  const page = Number(params.get('page') ?? '0');
  const stages = definitions.data?.find((d) => d.id === flowId)?.stages ?? definitions.data?.[0]?.stages ?? [];

  const flows = useFlowSearch({
    from: range.from,
    to: range.to,
    flowId: flowId || undefined,
    stage: stage || undefined,
    status: status || undefined,
    hasAnomaly: hasAnomaly ? hasAnomaly === 'true' : undefined,
    page,
    size: 25,
  });

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    next.delete('page');
    setParams(next);
  }

  async function onSearch(event: FormEvent) {
    event.preventDefault();
    if (!input.trim()) {
      return;
    }
    setDetecting(true);
    setDetectError(null);
    try {
      const userExists = async (id: string) => {
        const a = await getParsed(`/api/users/${encodeURIComponent(id)}/activity`, UserActivitySchema, { from: range.from, to: range.to });
        return a.sessions.length + a.flows.length + a.failedSignIns.length + a.permissionDenials.length > 0;
      };
      const target = await detect(input, definitions.data ?? [], userExists);
      const withRange = target.kind === 'userId' || target.to.startsWith('/logs')
        ? `${target.to}${target.to.includes('?') ? '&' : '?'}from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`
        : target.to;
      navigate(withRange);
    } catch (error) {
      setDetectError(error instanceof Error ? error : new Error(String(error)));
    } finally {
      setDetecting(false);
    }
  }

  return (
    <div className="view-fade space-y-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-ink-primary">Search</h1>
        <p className="mt-1 text-sm text-ink-secondary">
          Type a user ID, flow reference, request ID, session, client IP, external correlation ID or free text.
        </p>
      </div>

      <form onSubmit={onSearch} className="flex gap-2" role="search">
        <label htmlFor="search-input" className="sr-only">
          Search
        </label>
        <input
          id="search-input"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="usr-1042, REQ-20260515-000944, 0c12301b-…, 10.20.4.17 or any text"
          className="flex-1 rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-panel)] px-3 py-2 text-sm text-ink-primary placeholder:text-ink-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
        />
        <Button type="submit" disabled={detecting || definitions.isPending}>
          <SearchIcon className="h-4 w-4" aria-hidden />
          {detecting ? 'Searching…' : 'Search'}
        </Button>
      </form>
      {detectError && <ErrorPanel error={detectError} />}

      <Card>
        <CardHeader title="Flows" />
        <div className="flex flex-wrap items-end gap-3 border-b border-[var(--border-hairline)] px-4 py-3 text-xs">
          <Select label="Flow" value={flowId} onChange={(v) => setFilter('flowId', v)}
                  options={(definitions.data ?? []).map((d) => [d.id, d.id])} />
          <Select label="Stage" value={stage} onChange={(v) => setFilter('stage', v)} options={stages.map((s) => [s.name, s.name])} />
          <Select label="Status" value={status} onChange={(v) => setFilter('status', v)}
                  options={(stage ? [...STATUSES, 'NOT_REACHED'] : STATUSES).map((s) => [s, s])} />
          <Select label="Anomaly" value={hasAnomaly} onChange={(v) => setFilter('hasAnomaly', v)}
                  options={[['true', 'With anomalies'], ['false', 'Any']]} />
          <TimeRangePicker value={range} onChange={setRange} />
        </div>
        <DataState query={flows} isEmpty={(d) => d.items.length === 0} emptyMessage="No flows in this range.">
          {(data) => (
            <>
              <Table>
                <thead>
                  <tr>
                    <Th>Flow</Th>
                    <Th>Status</Th>
                    <Th>Actors</Th>
                    <Th>First seen</Th>
                    <Th>Amount</Th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((f) => (
                    <tr key={f.flowRef}>
                      <Td>
                        <Link className="font-mono text-primary hover:underline" to={`/traces/${encodeURIComponent(f.flowRef)}`}>
                          {f.flowRef}
                        </Link>
                        <div className="text-xs text-ink-secondary">{f.flowId}</div>
                      </Td>
                      <Td>
                        <StatusBadge status={f.overallStatus} />
                      </Td>
                      <Td className="text-xs">
                        {Object.entries(f.actors).map(([role, user]) => (
                          <div key={role}>
                            <span className="text-ink-secondary">{role}</span>{' '}
                            <Link className="font-mono text-primary hover:underline" to={`/users/${encodeURIComponent(user)}`}>
                              {user}
                            </Link>
                          </div>
                        ))}
                      </Td>
                      <Td className="whitespace-nowrap text-xs" title={f.firstSeen ?? undefined}>
                        {formatTime(f.firstSeen)}
                      </Td>
                      <Td className="whitespace-nowrap text-xs">
                        {f.amount !== null ? `${f.amount.toFixed(2)} ${f.currency ?? ''}` : '—'}
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements}
                          lowerBound={data.totalIsLowerBound} onPage={(p) => setFilter('page', String(p))} />
            </>
          )}
        </DataState>
      </Card>
    </div>
  );
}

function Select({ label, value, onChange, options }: { label: string; value: string; onChange: (v: string) => void; options: Array<[string, string]> }) {
  return (
    <label className="flex flex-col gap-1 text-ink-secondary">
      {label}
      <select
        className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-2 py-1.5 text-sm text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">All</option>
        {options.map(([v, l]) => (
          <option key={v} value={v}>
            {l}
          </option>
        ))}
      </select>
    </label>
  );
}
