import { Link, useParams } from 'react-router-dom';
import { useRequestLookup } from '@/api/hooks';
import { LogLinesTable } from '@/features/logs/LogLinesTable';
import { Card, CardHeader } from '@/shared/ui/Card';
import { DataState } from '@/shared/ui/DataState';

/** Request view (section 12.2): one HTTP call across services, with a link to its flow when it has one (F-10a). */
export function RequestPage() {
  const { requestId = '' } = useParams();
  const lookup = useRequestLookup(requestId);
  return (
    <div className="view-fade space-y-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-ink-primary">
          Request <span className="font-mono text-base">{requestId}</span>
        </h1>
        <p className="mt-1 text-sm text-ink-secondary">Every line that carries this request ID, across services.</p>
      </div>
      <DataState query={lookup} isEmpty={(d) => d.lines.length === 0 && !d.flowRef} emptyMessage="No lines carry this request ID.">
        {(d) => (
          <Card>
            <CardHeader
              title={`${d.lines.length} lines`}
              actions={
                d.flowRef ? (
                  <Link className="text-sm text-primary hover:underline" to={`/traces/${encodeURIComponent(d.flowRef)}`}>
                    Open flow {d.flowRef}
                  </Link>
                ) : (
                  <span className="text-sm text-ink-secondary">This request is not part of a flow</span>
                )
              }
            />
            <LogLinesTable lines={d.lines} />
          </Card>
        )}
      </DataState>
    </div>
  );
}
