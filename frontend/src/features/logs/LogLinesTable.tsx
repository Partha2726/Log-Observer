import { Link } from 'react-router-dom';
import type { LogLine } from '@/api/schemas';
import { Badge } from '@/shared/ui/Badge';
import { Highlight } from '@/shared/ui/Highlight';
import { Table, Td, Th } from '@/shared/ui/Table';
import { formatTime } from '@/shared/format/time';

const LEVEL_TONE: Record<string, 'red' | 'amber' | 'slate'> = { ERROR: 'red', FATAL: 'red', WARN: 'amber' };

/** Parsed log lines with a link to the trace (when the line carries a flowRef) or the request view. */
export function LogLinesTable({ lines }: { lines: LogLine[] }) {
  return (
    <Table>
      <thead>
        <tr>
          <Th>Time</Th>
          <Th>Service</Th>
          <Th>Level</Th>
          <Th>Stage</Th>
          <Th>Message</Th>
          <Th>Open</Th>
        </tr>
      </thead>
      <tbody>
        {lines.map((l) => (
          <tr key={l.docId}>
            <Td className="whitespace-nowrap text-xs" title={l.ts}>
              {formatTime(l.ts)}
            </Td>
            <Td className="text-xs">
              {l.service}
              <div className="text-ink-secondary">{l.host}</div>
            </Td>
            <Td>{l.level && <Badge tone={LEVEL_TONE[l.level] ?? 'slate'}>{l.level}</Badge>}</Td>
            <Td className="text-xs">{l.stage ?? <span className="text-ink-secondary">—</span>}</Td>
            <Td className="max-w-xl">
              <pre className="whitespace-pre-wrap break-all font-mono text-xs text-ink-primary">
                {l.highlight ? <Highlight text={l.highlight} /> : l.snippet}
              </pre>
              <div className="mt-1 text-[11px] text-ink-secondary">
                {l.sourceFile}
                {l.eventAction && <span className="ml-2">{l.eventAction}</span>}
              </div>
            </Td>
            <Td className="whitespace-nowrap text-xs">
              {l.flowRefs.map((r) => (
                <div key={r}>
                  <Link className="font-mono text-primary hover:underline" to={`/traces/${encodeURIComponent(r)}`}>
                    {r}
                  </Link>
                </div>
              ))}
              {l.requestId && (
                <Link className="text-primary hover:underline" to={`/requests/${l.requestId}`}>
                  request
                </Link>
              )}
            </Td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}
