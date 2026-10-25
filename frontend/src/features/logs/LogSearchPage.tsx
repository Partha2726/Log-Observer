import { useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Search } from 'lucide-react';
import { useFlowDefinitions, useLogSearch } from '@/api/hooks';
import { LogLinesTable } from '@/features/logs/LogLinesTable';
import { useRangeParams } from '@/shared/routing/useRange';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { DataState } from '@/shared/ui/DataState';
import { Pagination } from '@/shared/ui/Pagination';
import { TimeRangePicker } from '@/shared/ui/TimeRangePicker';

const FILTERS = ['service', 'host', 'clientIp'] as const;

/** Log search (section 12.2): free text over Elasticsearch with service, host, level, stage and client IP filters. */
export function LogSearchPage() {
  const [params, setParams] = useSearchParams();
  const [range, setRange] = useRangeParams();
  const definitions = useFlowDefinitions();
  const [text, setText] = useState(params.get('q') ?? '');
  const page = Number(params.get('page') ?? '0');
  const stages = [...new Set((definitions.data ?? []).flatMap((d) => d.stages.map((s) => s.name)))];

  const search = useLogSearch({
    q: params.get('q') ?? undefined,
    service: params.get('service') ?? undefined,
    host: params.get('host') ?? undefined,
    clientIp: params.get('clientIp') ?? undefined,
    level: params.get('level') ?? undefined,
    stage: params.get('stage') ?? undefined,
    from: range.from,
    to: range.to,
    page,
    size: 50,
  });

  function set(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    next.delete('page');
    setParams(next);
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    set('q', text.trim());
  }

  return (
    <div className="view-fade space-y-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-ink-primary">Log search</h1>
        <p className="mt-1 text-sm text-ink-secondary">Free-text search over the parsed log lines of every service.</p>
      </div>
      <form onSubmit={onSubmit} className="flex gap-2" role="search">
        <label htmlFor="log-q" className="sr-only">
          Text
        </label>
        <input
          id="log-q"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder='SQLTimeoutException, "Limit lookup failed", sess_…'
          className="flex-1 rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-panel)] px-3 py-2 text-sm text-ink-primary placeholder:text-ink-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
        />
        <Button type="submit">
          <Search className="h-4 w-4" aria-hidden />
          Search
        </Button>
      </form>
      <Card>
        <div className="flex flex-wrap items-end gap-3 border-b border-[var(--border-hairline)] px-4 py-3 text-xs">
          {FILTERS.map((f) => (
            <label key={f} className="flex flex-col gap-1 text-ink-secondary">
              {f === 'clientIp' ? 'Client IP' : f[0].toUpperCase() + f.slice(1)}
              <input
                defaultValue={params.get(f) ?? ''}
                onBlur={(e) => set(f, e.target.value.trim())}
                className="w-40 rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-2 py-1.5 text-sm text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
              />
            </label>
          ))}
          <label className="flex flex-col gap-1 text-ink-secondary">
            Level
            <select className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-2 py-1.5 text-sm text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
                    value={params.get('level') ?? ''} onChange={(e) => set('level', e.target.value)}>
              <option value="">All</option>
              {['ERROR', 'WARN', 'INFO', 'DEBUG'].map((l) => (
                <option key={l} value={l}>{l}</option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-ink-secondary">
            Stage
            <select className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-2 py-1.5 text-sm text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
                    value={params.get('stage') ?? ''} onChange={(e) => set('stage', e.target.value)}>
              <option value="">All</option>
              {stages.map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
          </label>
          <TimeRangePicker value={range} onChange={setRange} />
        </div>
        <DataState query={search} isEmpty={(d) => d.items.length === 0} emptyMessage="No log lines match.">
          {(data) => (
            <>
              <LogLinesTable lines={data.items} />
              <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements}
                          lowerBound={data.totalIsLowerBound} onPage={(p) => set('page', String(p))} />
            </>
          )}
        </DataState>
      </Card>
    </div>
  );
}
