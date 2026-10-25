import { describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderPage } from '@/test/render';
import { AiSummaryPanel } from '@/features/ai/AiSummaryPanel';
import events from '@/test/contract/events.json';

const REF = 'REQ-20260515-000020';
const DOC = (events as { items: { docId: string }[] }).items[0].docId;

export const completedSummary = {
  summaryId: 7,
  jobId: '00000000-0000-4000-8000-000000000007',
  flowRef: REF,
  status: 'COMPLETED',
  output: {
    headline: 'REQ-20260515-000020 failed at Approve (SQLTimeoutException)',
    status: 'FAILED',
    whatHappened: [{ stage: 'Approve', text: 'Stage Approve failed by usr-2001.', evidenceIds: ['ev1'] }],
    whereItFailed: { stage: 'Approve', component: 'c.a.b.l.LimitCheck', text: 'The limit check timed out.', evidenceIds: ['ev1'] },
    anomalyExplanations: [{ ruleId: 'R05', text: 'Rule R05 fired for stage Approve.', evidenceIds: ['ev1'] }],
    likelyCause: { text: 'SQLTimeoutException in c.a.b.l.LimitCheck stopped stage Approve.', confidence: 'MEDIUM', evidenceIds: ['ev1'] },
    suggestedChecks: ['Check the logs of c.a.b.l.LimitCheck around the first failure.'],
    uncertainty: 'Generated offline by the fake provider.',
  },
  provider: 'fake',
  model: 'fake-1',
  promptVersion: 'sum-1',
  contextHash: 'a'.repeat(64),
  createdAt: '2026-05-16T00:00:00.000Z',
  completedAt: '2026-05-16T00:00:01.000Z',
  errorCode: null,
  inputTokens: 900,
  outputTokens: 200,
  evidence: { ev1: DOC },
};

function open() {
  return renderPage(<AiSummaryPanel flowRef={REF} onCite={() => {}} />, { path: '/t', url: '/t' });
}

const notFound = () => HttpResponse.json({ code: 'NOT_FOUND', status: 404, message: 'No AI summary was requested' }, { status: 404 });

describe('AiSummaryPanel (UI-006)', () => {
  it('idle: offers Generate overview, then shows the requested state while the job runs', async () => {
    let requested = false;
    server.use(
      http.get(`/api/traces/${REF}/ai-summary`, () =>
        requested ? HttpResponse.json({ ...completedSummary, status: 'REQUESTED', output: null, completedAt: null }) : notFound()),
      http.post(`/api/traces/${REF}/ai-summary`, () => {
        requested = true;
        return HttpResponse.json({ jobId: completedSummary.jobId, summaryId: 7 }, { status: 202 });
      }),
      http.get(`/api/jobs/${completedSummary.jobId}`, () =>
        HttpResponse.json({ id: completedSummary.jobId, type: 'AI_SUMMARY', status: 'QUEUED', attemptCount: 0, maxAttempts: 3, errorCode: null,
          createdAt: '2026-05-16T00:00:00.000Z', startedAt: null, completedAt: null })),
    );
    open();
    fireEvent.click(await screen.findByRole('button', { name: /generate overview/i }));
    expect(await screen.findByText(/Overview requested/)).toBeInTheDocument();
  });

  it('running', async () => {
    server.use(http.get(`/api/traces/${REF}/ai-summary`, () => HttpResponse.json({ ...completedSummary, status: 'RUNNING', output: null, completedAt: null })));
    open();
    expect(await screen.findByText(/Generating the overview/)).toBeInTheDocument();
  });

  it('completed: the sections, the AI label, the model and Regenerate', async () => {
    server.use(http.get(`/api/traces/${REF}/ai-summary`, () => HttpResponse.json(completedSummary)));
    open();
    expect(await screen.findByText(completedSummary.output.headline)).toBeInTheDocument();
    expect(screen.getByText('AI-generated, verify before acting')).toBeInTheDocument();
    expect(screen.getByText(/fake-1/)).toBeInTheDocument();
    expect(screen.getByText('Where it failed')).toBeInTheDocument();
    expect(screen.getByText('R05')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Show evidence ev1' }).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: /regenerate/i })).toBeInTheDocument();
  });

  it('failed: the error message and Try again', async () => {
    server.use(http.get(`/api/traces/${REF}/ai-summary`, () =>
      HttpResponse.json({ ...completedSummary, status: 'FAILED', output: null, errorCode: 'AI_OUTPUT_INVALID' })));
    open();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('failed validation twice');
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
  });

  it('disabled: a 503 AI_DISABLED hides the AI controls', async () => {
    server.use(
      http.get(`/api/traces/${REF}/ai-summary`, notFound),
      http.post(`/api/traces/${REF}/ai-summary`, () =>
        HttpResponse.json({ code: 'AI_DISABLED', status: 503, message: 'AI features are disabled: AI_API_KEY is not set' }, { status: 503 })),
    );
    open();
    fireEvent.click(await screen.findByRole('button', { name: /generate overview/i }));
    await waitFor(() => expect(screen.getByText(/AI features are disabled on this server/)).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /generate overview/i })).toBeNull();
  });
});
