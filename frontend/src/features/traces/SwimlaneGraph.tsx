import { memo, useMemo, useRef } from 'react';
import { Background, Controls, Handle, Position, ReactFlow, type NodeProps } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Box, Cpu, Globe, Server, Square, User, XCircle } from 'lucide-react';
import type { TraceGraph } from '@/api/schemas';
import { StatusBadge } from '@/shared/ui/Badge';
import { cn } from '@/shared/ui/cn';
import { formatDuration } from '@/shared/format/time';
import { layout, LANE_LABEL_WIDTH, NODE_HEIGHT, NODE_WIDTH, type FlowNodeData, type LaneData } from '@/features/traces/graphLayout';

export type Selection = { kind: 'node'; id: string } | { kind: 'stage'; stage: string } | null;

const KIND_ICON = { ACTOR: User, SERVICE: Server, COMPONENT: Cpu, EXTERNAL: Globe, PLACEHOLDER: Square } as const;

const STATUS_STYLE: Record<string, string> = {
  OK: 'border-[var(--status-ok)] bg-[var(--surface-raised)]',
  WARN: 'border-[var(--status-warning)] bg-[var(--surface-raised)]',
  FAILED: 'border-[var(--status-failed)] bg-[var(--surface-raised)]',
  REJECTED: 'border-[var(--status-rejected)] bg-[var(--surface-raised)]',
  NOT_REACHED: 'border-[var(--ink-secondary)] border-dashed bg-[var(--surface-panel)]',
};

function FlowNodeView({ data, selected }: NodeProps) {
  const { node } = data as FlowNodeData;
  const Icon = KIND_ICON[node.kind];
  const uncertain = node.minConfidence !== undefined && node.minConfidence < 1;
  const tooltip = [
    `${node.kind} · ${node.status}`,
    node.service,
    node.logger,
    `${node.eventCount} lines`,
    uncertain ? `min confidence ${node.minConfidence?.toFixed(2)}` : null,
    node.exceptionType,
  ].filter(Boolean).join('\n');
  return (
    <div
      data-testid={`graph-node-${node.id}`}
      title={tooltip}
      style={{ width: NODE_WIDTH, height: NODE_HEIGHT }}
      className={cn(
        'flex flex-col justify-between rounded-sm border px-2 py-1.5 text-left text-[11px] text-ink-primary shadow-sm shadow-black/20',
        STATUS_STYLE[node.status],
        uncertain && 'border-dashed',
        node.isFirstFailure && 'border-[3px] border-[var(--status-failed)] ring-2 ring-[var(--status-failed)]/40',
        selected && 'outline outline-2 outline-[var(--accent)]',
      )}
    >
      <Handle type="target" position={Position.Left} className="!h-1.5 !w-1.5 !border-0 !bg-[var(--ink-secondary)]" />
      <div className="flex items-center gap-1 font-medium">
        <Icon className="h-3 w-3 flex-shrink-0 text-ink-secondary" aria-hidden />
        <span className="truncate">{node.label}</span>
      </div>
      <div className="flex items-center justify-between gap-1">
        <StatusBadge status={node.status} />
        <span className="text-ink-secondary">{node.eventCount} ln</span>
      </div>
      <div className="flex items-center gap-1 truncate text-[10px]">
        {node.isFirstFailure && (
          <span className="inline-flex items-center gap-0.5 rounded-sm bg-status-failed px-1 font-semibold text-[var(--surface-page)]">
            <XCircle className="h-2.5 w-2.5" aria-hidden />
            first failure
          </span>
        )}
        {node.exceptionType && <span className="truncate text-status-failed">{node.exceptionType.split('.').pop()}</span>}
        {uncertain && <span className="text-ink-secondary">conf {node.minConfidence?.toFixed(2)}</span>}
        {node.anomalyIds.length > 0 && <span className="rounded-sm bg-status-warning px-1 text-[var(--surface-page)]">{node.anomalyIds.length} anomaly</span>}
      </div>
      <Handle type="source" position={Position.Right} className="!h-1.5 !w-1.5 !border-0 !bg-[var(--ink-secondary)]" />
    </div>
  );
}

function LaneView({ data }: NodeProps) {
  const lane = data as LaneData;
  return (
    <div
      className={cn('h-full border-b border-[var(--border-hairline)]', lane.index % 2 === 0 ? 'bg-[var(--surface-panel)]' : 'bg-[var(--surface-page)]')}
      style={{ width: lane.width }}
    >
      <button
        type="button"
        data-lane={lane.stage}
        className="nodrag flex h-full flex-col justify-center gap-1 border-r border-[var(--border-hairline)] px-3 text-left text-xs transition-colors duration-150 hover:bg-[var(--surface-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[var(--accent)]"
        style={{ width: LANE_LABEL_WIDTH }}
        aria-label={`Show evidence of stage ${lane.stage}`}
      >
        <span className="text-sm font-semibold text-ink-primary">{lane.stage}</span>
        <StatusBadge status={lane.status} />
        <span className="font-mono text-ink-secondary">{lane.userId ?? '—'}</span>
        <span className="text-ink-secondary">{formatDuration(lane.durationMs)}</span>
      </button>
    </div>
  );
}

const nodeTypes = { flow: memo(FlowNodeView), lane: memo(LaneView) };

/**
 * Swimlane graph (sections 7.6, 12.3 and 12.5). Click or press Enter on a node to load
 * its evidence; click a lane header for the stage's evidence. Colour is never the only
 * signal: every node shows its status text and icon.
 */
export function SwimlaneGraph({ graph, selection, onSelect }: { graph: TraceGraph; selection: Selection; onSelect: (s: Selection) => void }) {
  const { nodes, edges } = useMemo(() => layout(graph), [graph]);
  // React Flow reports its own selection again whenever nodes re-render; only a change of node is a new choice
  // (otherwise clearing the selection from outside, for example by a citation, would be undone).
  const lastChosen = useRef<string | null>(null);
  const selectedNodes = useMemo(
    () => nodes.map((n) => (n.type === 'flow' ? { ...n, selected: selection?.kind === 'node' && selection.id === n.id } : n)),
    [nodes, selection],
  );
  // A fixed, readable zoom with panning: fitting a wide lane into the page made nodes unreadable.
  const zoom = 0.85;
  const height = graph.lanes.length * 130 * zoom + 40;
  return (
    <div style={{ height }} className="w-full" aria-label="Trace graph">
      <ReactFlow
        nodes={selectedNodes}
        edges={edges}
        nodeTypes={nodeTypes}
        defaultViewport={{ x: 8, y: 8, zoom }}
        minZoom={0.2}
        maxZoom={1.5}
        panOnScroll
        nodesDraggable={false}
        nodesConnectable={false}
        nodesFocusable
        elementsSelectable
        proOptions={{ hideAttribution: true }}
        onNodeClick={(event, node) => {
          if (node.type === 'flow') {
            lastChosen.current = node.id;
            onSelect({ kind: 'node', id: node.id });
          } else {
            const lane = (event.target as HTMLElement).closest('[data-lane]')?.getAttribute('data-lane');
            if (lane) {
              onSelect({ kind: 'stage', stage: lane });
            }
          }
        }}
        onSelectionChange={({ nodes: chosen }) => {
          const flow = chosen.find((n) => n.type === 'flow');
          const id = flow?.id ?? null;
          if (id === lastChosen.current) {
            return;
          }
          lastChosen.current = id;
          if (flow && !(selection?.kind === 'node' && selection.id === flow.id)) {
            onSelect({ kind: 'node', id: flow.id });
          }
        }}
      >
        <Background color="#252C3A" gap={24} />
        <Controls showInteractive={false} position="top-right" />
      </ReactFlow>
    </div>
  );
}

export function GraphLegend() {
  const items: Array<[string, string]> = [
    ['border-[var(--status-ok)] bg-[var(--surface-raised)]', 'OK'],
    ['border-[var(--status-warning)] bg-[var(--surface-raised)]', 'Warning lines'],
    ['border-[var(--status-failed)] bg-[var(--surface-raised)]', 'Failed'],
    ['border-[3px] border-[var(--status-failed)]', 'First failure'],
    ['border-[var(--status-rejected)] bg-[var(--surface-raised)]', 'Rejected'],
    ['border-dashed border-[var(--ink-secondary)]', 'Correlated (confidence below 1) or not reached'],
  ];
  return (
    <ul className="flex flex-wrap gap-3 px-4 py-2 text-[11px] text-ink-secondary" aria-label="Graph legend">
      {items.map(([cls, label]) => (
        <li key={label} className="flex items-center gap-1.5">
          <span className={cn('inline-block h-3 w-5 rounded-sm border', cls)} aria-hidden />
          {label}
        </li>
      ))}
      <li className="flex items-center gap-1.5">
        <Box className="h-3 w-3" aria-hidden /> Actor, service, component, external, placeholder icons
      </li>
      <li>Edges: grey sequence · blue call · violet dashed handoff · amber integration</li>
    </ul>
  );
}
