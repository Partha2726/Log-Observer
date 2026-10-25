import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getParsed } from '@/api/http';
import { EvidencePageSchema } from '@/api/schemas';
import { ChevronDown, ChevronRight, Clock } from 'lucide-react';
import { useTraceEvents } from '@/api/hooks';
import type { EvidenceLine } from '@/api/schemas';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card, CardHeader } from '@/shared/ui/Card';
import { DataState } from '@/shared/ui/DataState';
import { Pagination } from '@/shared/ui/Pagination';
import { formatTime } from '@/shared/format/time';
import type { Selection } from '@/features/traces/SwimlaneGraph';

/**
 * Evidence panel (sections 12.3 and 12.6): the lines behind the selected node or stage,
 * each with its service, host and evidence badge (OBSERVED, or CORRELATED with its
 * confidence, exactly as the backend gives it). Stack traces collapse; every line has a
 * raw or parsed view. Lines Elasticsearch no longer holds show as "evidence expired".
 */
/** A cited line to show: its document ID, and a counter so clicking the same citation again re-focuses it. */
export type Focus = { docId: string; nonce: number } | null;

const FOCUS_PAGE_SIZE = 100;

/** Finds the page (of all evidence, 100 per page) that holds a document, reading pages in order. */
function useLocate(flowRef: string, focus: Focus) {
  return useQuery({
    queryKey: ['trace-events-locate', flowRef, focus?.docId],
    enabled: focus !== null,
    queryFn: async () => {
      for (let page = 0; page < 50; page++) {
        const p = await getParsed(`/api/traces/${encodeURIComponent(flowRef)}/events`, EvidencePageSchema, { page, size: FOCUS_PAGE_SIZE });
        if (p.items.some((l) => l.docId === focus?.docId)) {
          return page;
        }
        if (page + 1 >= p.totalPages) {
          break;
        }
      }
      return null;
    },
  });
}

export function EvidencePanel({ flowRef, selection, onClear, focus = null }: { flowRef: string; selection: Selection; onClear: () => void; focus?: Focus }) {
  const [page, setPage] = useState(0);
  const [evidenceClass, setEvidenceClass] = useState('');
  const located = useLocate(flowRef, focus);
  const focusing = focus !== null && located.data !== undefined && located.data !== null;
  const params = focusing
    ? { page: located.data as number, size: FOCUS_PAGE_SIZE }
    : {
        nodeId: selection?.kind === 'node' ? selection.id : undefined,
        stage: selection?.kind === 'stage' ? selection.stage : undefined,
        evidenceClass: evidenceClass || undefined,
        page,
        size: 50,
      };
  const events = useTraceEvents(flowRef, params, true);
  const listRef = useRef<HTMLOListElement>(null);
  useEffect(() => {
    if (!focus || !events.data || events.isPlaceholderData) {
      return;
    }
    const el = listRef.current?.querySelector(`[data-doc-id="${focus.docId}"]`);
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [focus, events.data, events.isPlaceholderData]);
  const title = focusing
    ? 'Cited evidence'
    : selection === null ? 'All evidence' : selection.kind === 'node' ? `Evidence of node ${selection.id}` : `Evidence of stage ${selection.stage}`;

  return (
    <Card>
      <CardHeader
        title={title}
        actions={
          <div className="flex items-center gap-2 text-xs">
            <label className="flex items-center gap-1 text-ink-secondary">
              Class
              <select
                className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-1.5 py-1 text-xs text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
                value={evidenceClass}
                onChange={(e) => {
                  setEvidenceClass(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">All</option>
                <option value="OBSERVED">OBSERVED</option>
                <option value="CORRELATED">CORRELATED</option>
              </select>
            </label>
            {focus !== null && located.data === null && <span className="text-status-warning">cited line not found in this trace</span>}
            {selection && (
              <Button variant="ghost" onClick={onClear}>
                Show all
              </Button>
            )}
          </div>
        }
      />
      <DataState query={events} isEmpty={(d) => d.items.length === 0} emptyMessage="No lines for this selection.">
        {(data) => (
          <>
            <ol ref={listRef} className="divide-y divide-[var(--border-hairline)]" aria-label="Evidence lines">
              {data.items.map((line) => (
                <EvidenceRow key={line.docId} line={line} highlighted={focus?.docId === line.docId} />
              ))}
            </ol>
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements}
                        lowerBound={data.totalIsLowerBound} onPage={setPage} />
          </>
        )}
      </DataState>
    </Card>
  );
}

export function EvidenceBadge({ line }: { line: Pick<EvidenceLine, 'evidenceClass' | 'confidence' | 'linkReason'> }) {
  return line.evidenceClass === 'OBSERVED' ? (
    <Badge tone="green" title={`linked by ${line.linkReason}`}>OBSERVED</Badge>
  ) : (
    <Badge tone="amber" title={`linked by ${line.linkReason}`}>
      CORRELATED {line.confidence.toFixed(2)}
    </Badge>
  );
}

function EvidenceRow({ line, highlighted = false }: { line: EvidenceLine; highlighted?: boolean }) {
  const [view, setView] = useState<'raw' | 'parsed'>('raw');
  const [expanded, setExpanded] = useState(false);
  if (line.expired) {
    return (
      <li className="flex items-center gap-2 px-4 py-2 text-xs text-ink-secondary" data-expired="true">
        <Clock className="h-3.5 w-3.5" aria-hidden />
        <span>{formatTime(line.ts)}</span>
        <span>{line.service}</span>
        <EvidenceBadge line={line} />
        <span className="italic">evidence expired (no longer in the log store)</span>
      </li>
    );
  }
  const lines = (line.message ?? '').split('\n');
  const collapsible = line.isStacktrace && lines.length > 1;
  const shown = collapsible && !expanded ? lines.slice(0, 2).join('\n') : line.message ?? '';
  return (
    <li className={highlighted ? 'bg-[var(--surface-hover)] px-4 py-2 ring-2 ring-inset ring-[var(--accent)]' : 'px-4 py-2'} data-doc-id={line.docId}
        data-highlighted={highlighted || undefined} aria-current={highlighted || undefined}>
      <div className="flex flex-wrap items-center gap-2 text-xs text-ink-secondary">
        <span className="whitespace-nowrap text-ink-primary" title={line.ts}>
          {formatTime(line.ts)}
        </span>
        <span className="text-ink-primary">{line.service}</span>
        <span>{line.host}</span>
        {line.level && <Badge tone={line.level === 'ERROR' ? 'red' : line.level === 'WARN' ? 'amber' : 'slate'}>{line.level}</Badge>}
        <EvidenceBadge line={line} />
        <span className="text-ink-secondary">{line.stage}</span>
        {line.logger && <span className="font-mono text-ink-secondary">{line.logger}</span>}
        <div className="ml-auto flex gap-1" role="group" aria-label="View">
          <Button variant={view === 'raw' ? 'secondary' : 'ghost'} className="px-2 py-0.5 text-[11px]" onClick={() => setView('raw')} aria-pressed={view === 'raw'}>
            Raw
          </Button>
          <Button variant={view === 'parsed' ? 'secondary' : 'ghost'} className="px-2 py-0.5 text-[11px]" onClick={() => setView('parsed')} aria-pressed={view === 'parsed'}>
            Parsed
          </Button>
        </div>
      </div>
      {view === 'raw' ? (
        <div className="mt-1">
          <pre className="whitespace-pre-wrap break-all font-mono text-xs text-ink-primary">{shown}</pre>
          {collapsible && (
            <button type="button" className="mt-1 inline-flex items-center gap-1 text-xs text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]" onClick={() => setExpanded(!expanded)} aria-expanded={expanded}>
              {expanded ? <ChevronDown className="h-3 w-3" aria-hidden /> : <ChevronRight className="h-3 w-3" aria-hidden />}
              {expanded ? 'Hide stack trace' : `Show stack trace (${lines.length - 2} more lines)`}
            </button>
          )}
        </div>
      ) : (
        <dl className="mt-1 grid grid-cols-[max-content_1fr] gap-x-3 gap-y-0.5 font-mono text-xs">
          {Object.entries(line.parsed).map(([key, value]) => (
            <div key={key} className="contents">
              <dt className="text-ink-secondary">{key}</dt>
              <dd className="whitespace-pre-wrap break-all text-ink-primary">{typeof value === 'string' ? value : JSON.stringify(value)}</dd>
            </div>
          ))}
          <div className="contents">
            <dt className="text-ink-secondary">source</dt>
            <dd className="text-ink-secondary">{line.sourceFile}</dd>
          </div>
        </dl>
      )}
    </li>
  );
}
