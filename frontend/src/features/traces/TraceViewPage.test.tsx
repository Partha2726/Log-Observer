import { describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { server } from '@/test/server';
import { renderPage } from '@/test/render';
import { TraceViewPage } from '@/features/traces/TraceViewPage';
import { layout } from '@/features/traces/graphLayout';
import type { TraceGraph } from '@/api/schemas';
import trace from '@/test/contract/trace.json';
import graph from '@/test/contract/graph.json';
import events from '@/test/contract/events.json';
import definitions from '@/test/contract/definitions.json';
import { completedSummary } from '@/features/ai/AiSummaryPanel.test';

const REF = (trace as { flowRef: string }).flowRef;
const g = graph as unknown as TraceGraph;

function handlers(overrides: { trace?: object; graph?: object; events?: object; summary?: object } = {}, seen: string[] = []) {
  server.use(
    http.get(`/api/traces/${REF}/ai-summary`, () =>
      overrides.summary ? HttpResponse.json(overrides.summary)
        : HttpResponse.json({ code: 'NOT_FOUND', status: 404, message: 'No AI summary was requested' }, { status: 404 })),
    http.get('/api/flows/definitions', () => HttpResponse.json(definitions)),
    http.get(`/api/traces/${REF}`, async () => {
      await delay(20);
      return HttpResponse.json(overrides.trace ?? trace);
    }),
    http.get(`/api/traces/${REF}/graph`, () => HttpResponse.json(overrides.graph ?? graph)),
    http.get(`/api/traces/${REF}/events`, ({ request }) => {
      seen.push(new URL(request.url).search);
      return HttpResponse.json(overrides.events ?? events);
    }),
  );
}

function open() {
  return renderPage(<TraceViewPage />, { path: '/traces/:flowRef', url: `/traces/${REF}` });
}

describe('TraceViewPage', () => {
  it('UI-002: loading, then header, graph and evidence', async () => {
    handlers();
    open();
    expect(screen.getByRole('status')).toHaveTextContent('Assembling');
    expect(await screen.findByRole('heading', { name: REF })).toBeInTheDocument();
    expect(await screen.findByLabelText('Trace graph')).toBeInTheDocument();
    expect(await screen.findByLabelText('Evidence lines')).toBeInTheDocument();
    expect(screen.getByText(/First failure in/)).toHaveTextContent('c.a.b.l.LimitCheck');
  });

  it('UI-002: an unknown reference shows the error with its request ID and Retry', async () => {
    server.use(
      http.get('/api/flows/definitions', () => HttpResponse.json(definitions)),
      http.get(`/api/traces/${REF}`, () =>
        HttpResponse.json({ code: 'TRACE_NOT_FOUND', status: 404, message: 'No logs for this reference', requestId: 'req-404' }, { status: 404 })),
    );
    open();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No logs for this reference');
    expect(alert).toHaveTextContent('req-404');
    expect(within(alert).getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('UI-003: every graph node and edge maps one-to-one to the graph JSON', async () => {
    const { nodes, edges } = layout(g);
    const flowNodes = nodes.filter((n) => n.type === 'flow');
    expect(flowNodes.map((n) => n.id)).toEqual(g.nodes.map((n) => n.id));
    expect(edges.map((e) => [e.id, e.source, e.target])).toEqual(g.edges.map((e) => [e.id, e.source, e.target]));
    expect(nodes.filter((n) => n.type === 'lane').map((n) => n.id)).toEqual(g.lanes.map((l) => `lane-${l.stage}`));

    handlers();
    open();
    for (const n of g.nodes) {
      expect(await screen.findByTestId(`graph-node-${n.id}`)).toBeInTheDocument();
    }
    expect(screen.getAllByTestId(/^graph-node-/)).toHaveLength(g.nodes.length);
    const first = g.nodes.find((n) => n.isFirstFailure)!;
    expect(screen.getByTestId(`graph-node-${first.id}`)).toHaveTextContent('first failure');
  });

  it('UI-001: unusual values render exactly as given, with nothing derived in the browser', async () => {
    const oddTrace = { ...trace, overallStatus: 'COMPLETED', observedRatio: 0.12, excludedCount: 7 };
    const oddGraph = {
      ...g,
      nodes: g.nodes.map((n, i) => (i === 0 ? { ...n, status: 'WARN', isFirstFailure: true, minConfidence: 0.37, label: 'odd node' } : n)),
    };
    const oddEvents = {
      ...events,
      items: (events as { items: object[] }).items.slice(0, 1).map((l) => ({ ...l, evidenceClass: 'CORRELATED', confidence: 0.37 })),
      totalElements: 1,
      totalPages: 1,
    };
    handlers({ trace: oddTrace, graph: oddGraph, events: oddEvents });
    open();
    const header = await screen.findByRole('heading', { name: REF });
    expect(header.parentElement).toHaveTextContent('COMPLETED');
    expect(screen.getByText(/observed ratio 0\.12/)).toBeInTheDocument();
    expect(screen.getByText(/7 excluded/)).toBeInTheDocument();
    const node = await screen.findByTestId(`graph-node-${g.nodes[0].id}`);
    expect(node).toHaveTextContent('odd node');
    expect(node).toHaveTextContent('WARN');
    expect(node).toHaveTextContent('first failure');
    expect(node).toHaveTextContent('conf 0.37');
    expect(await screen.findByText('CORRELATED 0.37')).toBeInTheDocument();
  });

  it('loads a node\'s evidence when it is clicked and a stage\'s when its lane header is clicked', async () => {
    const seen: string[] = [];
    handlers({}, seen);
    open();
    const first = g.nodes.find((n) => n.isFirstFailure)!;
    fireEvent.click(await screen.findByTestId(`graph-node-${first.id}`));
    await waitFor(() => expect(seen.some((s) => s.includes(`nodeId=${first.id}`))).toBe(true));
    // React Flow keeps unmeasured nodes out of the accessibility tree in jsdom, so select the lane header by its attribute.
    const laneHeader = document.querySelector('[data-lane="Approve"]');
    expect(laneHeader).toHaveAttribute('aria-label', 'Show evidence of stage Approve');
    fireEvent.click(laneHeader!);
    await waitFor(() => expect(seen.some((s) => s.includes('stage=Approve'))).toBe(true));
  });

  it('collapses stack traces, toggles raw and parsed, and greys out expired evidence', async () => {
    const items = (events as { items: Array<Record<string, unknown>> }).items;
    const stack = items.find((l) => l.isStacktrace) ?? items[0];
    handlers({
      events: {
        ...events,
        items: [stack, { ...items[0], docId: 'gone', expired: true, message: null, parsed: {} }],
        totalElements: 2,
        totalPages: 1,
      },
    });
    open();
    const toggle = await screen.findByRole('button', { name: /Show stack trace/ });
    fireEvent.click(toggle);
    expect(screen.getByRole('button', { name: /Hide stack trace/ })).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button', { name: 'Parsed' })[0]);
    expect(screen.getByText('source')).toBeInTheDocument();
    expect(screen.getByText(/evidence expired/)).toBeInTheDocument();
  });

  it('UI-004: clicking a citation in the AI overview shows and highlights its line', async () => {
    handlers({ summary: completedSummary });
    open();
    const cite = (await screen.findAllByRole('button', { name: 'Show evidence ev1' }))[0];
    fireEvent.click(cite);
    await waitFor(() => {
      const row = document.querySelector(`[data-doc-id="${completedSummary.evidence.ev1}"]`);
      expect(row).toHaveAttribute('data-highlighted', 'true');
    });
    expect(await screen.findByText('Cited evidence')).toBeInTheDocument();
  });
});
