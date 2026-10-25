import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useFlowDefinitions, useTrace, useTraceGraph } from '@/api/hooks';
import { getParsed } from '@/api/http';
import { TraceSchema } from '@/api/schemas';
import { Card, CardHeader } from '@/shared/ui/Card';
import { DataState, ErrorPanel } from '@/shared/ui/DataState';
import { EvidencePanel, type Focus } from '@/features/traces/EvidencePanel';
import { AiSummaryPanel } from '@/features/ai/AiSummaryPanel';
import { AssistantDrawer } from '@/features/ai/AssistantDrawer';
import { Button } from '@/shared/ui/Button';
import { MessageSquare } from 'lucide-react';
import { GraphLegend, SwimlaneGraph, type Selection } from '@/features/traces/SwimlaneGraph';
import { TraceHeader } from '@/features/traces/TraceHeader';

/**
 * Trace view (section 12.3): header, swimlane graph and evidence panel. The browser
 * derives nothing the backend owns (UI-001): statuses, confidences and the graph are
 * rendered exactly as received. The AI overview and the assistant cite log lines; a click on a
 * citation shows and highlights that line in the evidence panel (UI-004).
 */
export function TraceViewPage() {
  const { flowRef = '' } = useParams();
  const trace = useTrace(flowRef);
  const graph = useTraceGraph(flowRef, trace.isSuccess);
  const definitions = useFlowDefinitions();
  const queryClient = useQueryClient();
  const [selection, setSelection] = useState<Selection>(null);
  const [rebuilding, setRebuilding] = useState(false);
  const [rebuildError, setRebuildError] = useState<Error | null>(null);
  const [focus, setFocus] = useState<Focus>(null);
  const [assistantOpen, setAssistantOpen] = useState(false);
  const cite = (docId: string) => {
    setSelection(null);
    setFocus((f) => ({ docId, nonce: (f?.nonce ?? 0) + 1 }));
  };

  async function rebuild() {
    setRebuilding(true);
    setRebuildError(null);
    try {
      const fresh = await getParsed(`/api/traces/${encodeURIComponent(flowRef)}`, TraceSchema, { rebuild: true });
      queryClient.setQueryData(['trace', flowRef], fresh);
      await queryClient.invalidateQueries({ queryKey: ['trace-graph', flowRef] });
      await queryClient.invalidateQueries({ queryKey: ['trace-events', flowRef] });
    } catch (error) {
      setRebuildError(error instanceof Error ? error : new Error(String(error)));
    } finally {
      setRebuilding(false);
    }
  }

  return (
    <div className="view-fade space-y-4">
      <DataState query={trace} loadingLabel="Assembling the trace…">
        {(t) => (
          <>
            <TraceHeader trace={t} definition={definitions.data?.find((d) => d.id === t.flowId)} onRebuild={() => void rebuild()} rebuilding={rebuilding} />
            {rebuildError && <ErrorPanel error={rebuildError} />}
            <Card>
              <CardHeader title="Flow graph" />
              <DataState query={graph} isEmpty={(g) => g.nodes.length === 0} emptyMessage="The graph has no nodes.">
                {(g) => (
                  <>
                    <SwimlaneGraph graph={g} selection={selection} onSelect={(sel) => {
                      setFocus(null);
                      setSelection(sel);
                    }} />
                    <GraphLegend />
                  </>
                )}
              </DataState>
            </Card>
            <div className="flex justify-end">
              <Button variant="secondary" onClick={() => setAssistantOpen(true)}>
                <MessageSquare className="h-4 w-4" aria-hidden /> Ask the assistant
              </Button>
            </div>
            <AiSummaryPanel flowRef={flowRef} onCite={cite} />
            <EvidencePanel
              flowRef={flowRef}
              selection={selection}
              focus={focus}
              onClear={() => {
                setSelection(null);
                setFocus(null);
              }}
            />
            {assistantOpen && <AssistantDrawer flowRef={flowRef} onCite={cite} onClose={() => setAssistantOpen(false)} />}
          </>
        )}
      </DataState>
    </div>
  );
}
