import { Link } from 'react-router-dom';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import type { FlowDefinition, Trace } from '@/api/schemas';
import { Badge, StatusBadge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { formatDuration, formatTime } from '@/shared/format/time';

/** Trace header (section 12.3): reference, flow, status, actors by role, duration, diagnostics. All values come from the API. */
export function TraceHeader({ trace, definition, onRebuild, rebuilding }: {
  trace: Trace;
  definition?: FlowDefinition;
  onRebuild: () => void;
  rebuilding: boolean;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4">
      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="font-mono text-2xl font-semibold tracking-tight text-ink-primary">{trace.flowRef}</h1>
          <StatusBadge status={trace.overallStatus} />
          <Badge tone="blue">{definition ? `${definition.id} v${definition.version}` : trace.flowId}</Badge>
          {trace.truncated && (
            <Badge tone="amber" title="More than 5,000 lines carry this reference; only the first 5,000 were used">
              <AlertTriangle className="h-3 w-3" aria-hidden /> truncated
            </Badge>
          )}
        </div>
        <dl className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-ink-secondary">
          {Object.entries(trace.actors).map(([role, user]) => (
            <div key={role} className="flex gap-1">
              <dt>{role}</dt>
              <dd>
                <Link className="font-mono text-primary hover:underline" to={`/users/${encodeURIComponent(user)}`}>
                  {user}
                </Link>
              </dd>
            </div>
          ))}
          <div className="flex gap-1">
            <dt>duration</dt>
            <dd className="text-ink-primary">{formatDuration(trace.durationMs)}</dd>
          </div>
          <div className="flex gap-1">
            <dt>from</dt>
            <dd className="text-ink-primary" title={trace.firstSeen}>{formatTime(trace.firstSeen)}</dd>
          </div>
          {trace.amount !== null && (
            <div className="flex gap-1">
              <dt>amount</dt>
              <dd className="text-ink-primary">
                {trace.amount.toFixed(2)} {trace.currency}
                {trace.counterpartyLast4 && <span className="ml-1 text-ink-secondary">to ACC-****{trace.counterpartyLast4}</span>}
              </dd>
            </div>
          )}
          <div className="flex gap-1">
            <dt>anomalies</dt>
            <dd className="text-ink-primary">{trace.anomalies.length}</dd>
          </div>
        </dl>
        {trace.firstFailure && (
          <p className="text-xs text-status-failed">
            First failure in <strong>{trace.firstFailure.stage}</strong>: {trace.firstFailure.component}
            {trace.firstFailure.exception && <> · {trace.firstFailure.exception}</>}
            {trace.firstFailure.httpStatus !== null && <> · HTTP {trace.firstFailure.httpStatus}</>}
          </p>
        )}
      </div>
      <div className="space-y-1 text-right text-xs text-ink-secondary">
        <div>
          {trace.linkCount} lines · observed ratio {trace.observedRatio?.toFixed(2) ?? '—'} · {trace.excludedCount} excluded below threshold
        </div>
        <div title={trace.assembledAt}>assembled {formatTime(trace.assembledAt)} · v{trace.assemblyVersion}</div>
        <Button variant="secondary" onClick={onRebuild} disabled={rebuilding}>
          <RefreshCw className={rebuilding ? 'h-3.5 w-3.5 animate-spin' : 'h-3.5 w-3.5'} aria-hidden />
          Re-assemble
        </Button>
      </div>
    </div>
  );
}
