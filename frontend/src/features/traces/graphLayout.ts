import type { Edge, Node } from '@xyflow/react';
import type { GraphNode, TraceGraph } from '@/api/schemas';

/**
 * Lays out graph_version 1 for React Flow (section 12.5). The backend owns the graph:
 * this only places it. Lanes are fixed horizontal bands in flow-definition order; a
 * node's x is its rank inside its lane, in the backend's node order (first timestamp).
 * Every backend node becomes exactly one "flow" node and every backend edge exactly
 * one edge (UI-003); lane bands are extra, non-interactive "lane" nodes.
 */
export const LANE_HEIGHT = 130;
export const LANE_LABEL_WIDTH = 190;
export const NODE_WIDTH = 190;
export const NODE_HEIGHT = 76;
export const NODE_GAP = 36;

export type FlowNodeData = { node: GraphNode; selected?: boolean } & Record<string, unknown>;
export type LaneData = TraceGraph['lanes'][number] & { index: number; width: number } & Record<string, unknown>;

export function layout(graph: TraceGraph): { nodes: Node[]; edges: Edge[] } {
  const laneIndex = new Map(graph.lanes.map((lane, i) => [lane.stage, i]));
  const rank = new Map<string, number>();
  const perLane = new Map<string, number>();
  for (const n of graph.nodes) {
    const r = perLane.get(n.stage) ?? 0;
    rank.set(n.id, r);
    perLane.set(n.stage, r + 1);
  }
  const widest = Math.max(1, ...perLane.values());
  const width = LANE_LABEL_WIDTH + widest * (NODE_WIDTH + NODE_GAP) + NODE_GAP;

  const lanes: Node[] = graph.lanes.map((lane, i) => ({
    id: `lane-${lane.stage}`,
    type: 'lane',
    position: { x: 0, y: i * LANE_HEIGHT },
    data: { ...lane, index: i, width } satisfies LaneData,
    draggable: false,
    selectable: false,
    focusable: false,
    zIndex: -1,
    style: { width, height: LANE_HEIGHT },
  }));

  const nodes: Node[] = graph.nodes.map((n) => ({
    id: n.id,
    type: 'flow',
    position: {
      x: LANE_LABEL_WIDTH + (rank.get(n.id) ?? 0) * (NODE_WIDTH + NODE_GAP) + NODE_GAP / 2,
      y: (laneIndex.get(n.stage) ?? 0) * LANE_HEIGHT + (LANE_HEIGHT - NODE_HEIGHT) / 2,
    },
    data: { node: n } satisfies FlowNodeData,
    draggable: false,
    width: NODE_WIDTH,
    height: NODE_HEIGHT,
  }));

  const edges: Edge[] = graph.edges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    label: e.label ?? undefined,
    data: { kind: e.kind, evidenceIds: e.evidenceIds },
    animated: e.kind === 'call',
    style: EDGE_STYLE[e.kind],
    labelStyle: { fill: '#E6EAF2', fontSize: 10 },
    labelBgStyle: { fill: '#11151D' },
  }));

  return { nodes: [...lanes, ...nodes], edges };
}

const EDGE_STYLE: Record<string, React.CSSProperties> = {
  sequence: { stroke: '#8B94A7' },
  handoff: { stroke: '#9A72E6', strokeDasharray: '6 4', strokeWidth: 2 },
  call: { stroke: '#4C8DFF' },
  integration: { stroke: '#D99A35', strokeWidth: 2 },
};
