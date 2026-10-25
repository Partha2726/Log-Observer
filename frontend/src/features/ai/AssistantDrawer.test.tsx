import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderPage } from '@/test/render';
import { AssistantDrawer } from '@/features/ai/AssistantDrawer';

const REF = 'REQ-20260515-000020';

describe('AssistantDrawer', () => {
  it('asks a suggested question and shows the answer, its citations and the tools used', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.post('/api/assistant/conversations', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({ conversationId: 'c-1' }, { status: 201 });
      }),
      http.post('/api/assistant/conversations/c-1/messages', () =>
        HttpResponse.json({
          answer: 'The limit check timed out [ev:4]. <b>not html</b>',
          citations: [{ ref: 'ev:4', docId: 'doc-4', stage: 'Approve', ts: '2026-05-15T10:39:48.750Z' }],
          toolCalls: [{ name: 'search_logs', args: '{"query":"timeout"}', resultCount: 3, durationMs: 12, refused: false }],
          usage: { inputTokens: 100, outputTokens: 20 },
        })),
    );
    const onCite = vi.fn();
    renderPage(<AssistantDrawer flowRef={REF} onCite={onCite} onClose={() => {}} />, { path: '/t', url: '/t' });
    fireEvent.click(screen.getByRole('button', { name: 'Why did this fail?' }));
    expect(await screen.findByText(/The limit check timed out/)).toHaveTextContent('<b>not html</b>');
    expect(bodies[0]).toEqual({ flowRef: REF });
    fireEvent.click(screen.getByRole('button', { name: 'Show evidence ev4' }));
    expect(onCite).toHaveBeenCalledWith('doc-4');
    fireEvent.click(screen.getByRole('button', { name: /tools used \(1\)/i }));
    expect(screen.getByText(/search_logs/)).toHaveTextContent('3 results');
  });

  it('shows an error such as AI_DISABLED or a budget refusal', async () => {
    server.use(http.post('/api/assistant/conversations', () =>
      HttpResponse.json({ code: 'AI_BUDGET_EXCEEDED', status: 429, message: 'Daily AI token budget used up' }, { status: 429 })));
    renderPage(<AssistantDrawer flowRef={REF} onCite={() => {}} onClose={() => {}} />, { path: '/t', url: '/t' });
    fireEvent.change(screen.getByLabelText('Question'), { target: { value: 'Hello?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('AI_BUDGET_EXCEEDED');
  });
});
