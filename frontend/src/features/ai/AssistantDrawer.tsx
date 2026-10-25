import { useState } from 'react';
import { ChevronDown, ChevronRight, MessageSquare, Send, X } from 'lucide-react';
import { createConversation, sendMessage } from '@/api/ai';
import { ApiRequestError } from '@/api/http';
import type { ChatAnswer } from '@/api/schemas';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { AiText } from '@/features/ai/AiText';

const SUGGESTED = ['Why did this fail?', 'Has this component failed today?', "Is this user's activity unusual?"];

type Turn = { question: string; answer?: ChatAnswer; error?: string };

/**
 * Assistant drawer (section 12.3): chat scoped to this trace, with suggested questions, citations that
 * show their log line, and an expandable list of the tools the assistant used.
 */
export function AssistantDrawer({ flowRef, onCite, onClose }: { flowRef: string; onCite: (docId: string) => void; onClose: () => void }) {
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);

  async function ask(question: string) {
    const q = question.trim();
    if (!q || busy) {
      return;
    }
    setBusy(true);
    setDraft('');
    setTurns((t) => [...t, { question: q }]);
    try {
      const id = conversationId ?? (await createConversation(flowRef));
      setConversationId(id);
      const answer = await sendMessage(id, q);
      setTurns((t) => t.map((turn, i) => (i === t.length - 1 ? { ...turn, answer } : turn)));
    } catch (error) {
      const message = error instanceof ApiRequestError ? `${error.message}${error.code ? ` (${error.code})` : ''}` : String(error);
      setTurns((t) => t.map((turn, i) => (i === t.length - 1 ? { ...turn, error: message } : turn)));
    } finally {
      setBusy(false);
    }
  }

  return (
    <aside className="fixed inset-y-0 right-0 z-40 flex w-full max-w-md flex-col border-l border-[var(--border-hairline)] bg-[var(--surface-panel)] shadow-2xl shadow-black/30" aria-label="Assistant">
      <div className="flex items-center justify-between border-b border-[var(--border-hairline)] px-4 py-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-ink-primary">
          <MessageSquare className="h-4 w-4" aria-hidden /> Assistant · <span className="font-mono">{flowRef}</span>
        </h2>
        <Button variant="ghost" onClick={onClose} aria-label="Close assistant">
          <X className="h-4 w-4" aria-hidden />
        </Button>
      </div>
      <div className="flex-1 space-y-4 overflow-y-auto px-4 py-3">
        <Badge tone="violet">AI-generated, verify before acting</Badge>
        {turns.length === 0 && (
          <div className="space-y-2">
            <p className="text-xs text-ink-secondary">Suggested questions</p>
            {SUGGESTED.map((s) => (
              <Button key={s} variant="secondary" className="w-full justify-start text-left" onClick={() => void ask(s)}>
                {s}
              </Button>
            ))}
          </div>
        )}
        {turns.map((t, i) => (
          <div key={i} className="space-y-2">
            <p className="ml-auto w-fit max-w-[85%] rounded-sm border border-[var(--accent)] bg-[var(--surface-raised)] px-3 py-2 text-sm text-ink-primary">{t.question}</p>
            {t.error ? (
              <p role="alert" className="text-sm text-status-failed">{t.error}</p>
            ) : t.answer ? (
              <AnswerView answer={t.answer} onCite={onCite} />
            ) : (
              <p className="text-sm text-ink-secondary" role="status">Thinking…</p>
            )}
          </div>
        ))}
      </div>
      <form
        className="flex gap-2 border-t border-[var(--border-hairline)] p-3"
        onSubmit={(e) => {
          e.preventDefault();
          void ask(draft);
        }}
      >
        <input
          className="flex-1 rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-3 py-1.5 text-sm text-ink-primary placeholder:text-ink-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
          placeholder="Ask about this trace"
          value={draft}
          maxLength={2000}
          onChange={(e) => setDraft(e.target.value)}
          aria-label="Question"
        />
        <Button type="submit" disabled={busy || draft.trim() === ''} aria-label="Send">
          <Send className="h-4 w-4" aria-hidden />
        </Button>
      </form>
    </aside>
  );
}

function AnswerView({ answer, onCite }: { answer: ChatAnswer; onCite: (docId: string) => void }) {
  const [toolsOpen, setToolsOpen] = useState(false);
  const docs = new Map(answer.citations.map((c) => [`ev${c.ref.slice(3)}`, c.docId]));
  return (
    <div className="space-y-2 rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-raised)] px-3 py-2">
      <AiText text={answer.answer} onCite={(ev) => docs.get(ev) && onCite(docs.get(ev)!)} citable={(ev) => docs.has(ev)} />
      {answer.toolCalls.length > 0 && (
        <div className="text-xs text-ink-secondary">
          <button type="button" className="inline-flex items-center gap-1 hover:text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]" onClick={() => setToolsOpen(!toolsOpen)} aria-expanded={toolsOpen}>
            {toolsOpen ? <ChevronDown className="h-3 w-3" aria-hidden /> : <ChevronRight className="h-3 w-3" aria-hidden />}
            Tools used ({answer.toolCalls.length})
          </button>
          {toolsOpen && (
            <ul className="mt-1 space-y-0.5 font-mono">
              {answer.toolCalls.map((c, i) => (
                <li key={i}>
                  {c.name}({c.args}) → {c.refused ? 'refused (limit reached)' : `${c.resultCount} results, ${c.durationMs} ms`}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
