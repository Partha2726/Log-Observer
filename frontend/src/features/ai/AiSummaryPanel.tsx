import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Bot, RefreshCw, ShieldAlert, Sparkles } from 'lucide-react';
import { isTerminalJob, requestSummary, useAiSummary, useJob } from '@/api/ai';
import { ApiRequestError } from '@/api/http';
import type { AiSummary, SummaryOutput } from '@/api/schemas';
import { Badge, StatusBadge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card, CardHeader } from '@/shared/ui/Card';
import { ErrorPanel } from '@/shared/ui/DataState';
import { formatTime } from '@/shared/format/time';
import { AiText, CitationButton } from '@/features/ai/AiText';

const ERROR_TEXT: Record<string, string> = {
  AI_PROVIDER_ERROR: 'AI provider busy; try again in a minute.',
  AI_OUTPUT_INVALID: 'The AI answer failed validation twice (for example it cited evidence that does not exist).',
  AI_BUDGET_EXCEEDED: 'Your daily AI token budget is used up.',
  AI_DISABLED: 'AI features are disabled.',
};

/**
 * AI overview of a trace (sections 11.4 and 12.3): idle, requested, running, completed, failed and
 * AI disabled. Every `evN` citation can be clicked to show its log line in the evidence panel.
 */
export function AiSummaryPanel({ flowRef, onCite }: { flowRef: string; onCite: (docId: string) => void }) {
  const summary = useAiSummary(flowRef);
  const queryClient = useQueryClient();
  const [jobId, setJobId] = useState<string | null>(null);
  const [requestError, setRequestError] = useState<ApiRequestError | null>(null);
  const [requesting, setRequesting] = useState(false);
  const job = useJob(jobId);

  const jobStatus = job.data?.status;
  useEffect(() => {
    // The job ended: fetch the stored summary (COMPLETED or FAILED).
    if (jobId && isTerminalJob(jobStatus)) {
      void queryClient.invalidateQueries({ queryKey: ['ai-summary', flowRef] });
    }
  }, [jobId, jobStatus, flowRef, queryClient]);

  async function generate(regenerate: boolean) {
    setRequesting(true);
    setRequestError(null);
    try {
      const r = await requestSummary(flowRef, regenerate);
      if ('status' in r) {
        queryClient.setQueryData(['ai-summary', flowRef], r);
      } else {
        setJobId(r.jobId);
        await queryClient.invalidateQueries({ queryKey: ['ai-summary', flowRef] });
      }
    } catch (error) {
      setRequestError(error instanceof ApiRequestError ? error : new ApiRequestError(String(error), { status: null, code: null, requestId: null }));
    } finally {
      setRequesting(false);
    }
  }

  const disabled = requestError?.code === 'AI_DISABLED';
  const s = summary.data;
  const inProgress = s?.status === 'REQUESTED' || s?.status === 'RUNNING';
  const state = disabled ? 'disabled' : summary.isPending ? 'loading' : !s ? 'idle' : s.status.toLowerCase();

  return (
    <Card data-ai-state={state}>
      <CardHeader
        title={
          <span className="inline-flex items-center gap-1.5">
            <Sparkles className="h-4 w-4 text-status-rejected" aria-hidden /> AI overview
          </span>
        }
        actions={
          s && !inProgress && !disabled ? (
            <Button variant="secondary" onClick={() => void generate(true)} disabled={requesting}>
              <RefreshCw className="h-3.5 w-3.5" aria-hidden /> Regenerate
            </Button>
          ) : null
        }
      />
      <div className="space-y-3 px-4 py-3">
        {disabled ? (
          <p className="flex items-center gap-2 text-sm text-ink-secondary" role="status">
            <ShieldAlert className="h-4 w-4" aria-hidden /> AI features are disabled on this server. {requestError?.message}
          </p>
        ) : summary.isError ? (
          <ErrorPanel error={summary.error} onRetry={() => void summary.refetch()} />
        ) : summary.isPending ? (
          <p className="text-sm text-ink-secondary" role="status">Loading the AI overview…</p>
        ) : !s ? (
          <div className="flex items-center gap-3">
            <Button onClick={() => void generate(false)} disabled={requesting}>
              <Bot className="h-4 w-4" aria-hidden /> Generate overview
            </Button>
            <span className="text-xs text-ink-secondary">The model sees only redacted evidence from this trace.</span>
          </div>
        ) : inProgress ? (
          <p className="text-sm text-ink-primary" role="status">
            {s.status === 'REQUESTED' ? 'Overview requested; waiting for a worker…' : 'Generating the overview…'}
            {job.data && <span className="ml-2 text-xs text-ink-secondary">job {job.data.status.toLowerCase()}</span>}
          </p>
        ) : s.status === 'FAILED' ? (
          <div role="alert" className="space-y-2 text-sm">
            <p className="text-status-failed">The overview could not be generated: {ERROR_TEXT[s.errorCode ?? ''] ?? s.errorCode}</p>
            <Button variant="secondary" onClick={() => void generate(true)} disabled={requesting}>
              Try again
            </Button>
          </div>
        ) : s.output ? (
          <SummaryView summary={s} output={s.output} onCite={(ev) => s.evidence[ev] && onCite(s.evidence[ev])} />
        ) : null}
        {requestError && !disabled && <ErrorPanel error={requestError} />}
      </div>
    </Card>
  );
}

function Cites({ ids, onCite, summary }: { ids: string[]; onCite: (ev: string) => void; summary: AiSummary }) {
  return (
    <>
      {ids.map((ev) => (
        <CitationButton key={ev} ev={ev} onCite={onCite} enabled={summary.evidence[ev] !== undefined} />
      ))}
    </>
  );
}

function SummaryView({ summary, output, onCite }: { summary: AiSummary; output: SummaryOutput; onCite: (ev: string) => void }) {
  return (
    <div className="space-y-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone="violet">AI-generated, verify before acting</Badge>
        <span className="text-xs text-ink-secondary">
          {summary.model} · {summary.promptVersion} · {formatTime(summary.completedAt)}
        </span>
      </div>
      <div className="flex items-center gap-2">
        <StatusBadge status={output.status} />
        <p className="font-medium text-ink-primary">{output.headline}</p>
      </div>
      <Section title="What happened">
        <ol className="space-y-1">
          {output.whatHappened.map((w, i) => (
            <li key={i}>
              <span className="mr-1 font-medium text-ink-primary">{w.stage}:</span>
              {w.text}
              <Cites ids={w.evidenceIds} onCite={onCite} summary={summary} />
            </li>
          ))}
        </ol>
      </Section>
      {output.whereItFailed && (
        <Section title="Where it failed">
          <p>
            <span className="mr-1 font-medium text-ink-primary">
              {output.whereItFailed.stage} · <span className="font-mono">{output.whereItFailed.component}</span>:
            </span>
            {output.whereItFailed.text}
            <Cites ids={output.whereItFailed.evidenceIds} onCite={onCite} summary={summary} />
          </p>
        </Section>
      )}
      {output.anomalyExplanations.length > 0 && (
        <Section title="Why the rules fired">
          <ul className="space-y-1">
            {output.anomalyExplanations.map((a, i) => (
              <li key={i}>
                <Badge tone="amber" className="mr-1">{a.ruleId}</Badge>
                {a.text}
                <Cites ids={a.evidenceIds} onCite={onCite} summary={summary} />
              </li>
            ))}
          </ul>
        </Section>
      )}
      <Section title="Likely cause">
        {output.likelyCause ? (
          <p>
            {output.likelyCause.text} <Badge tone="slate">confidence {output.likelyCause.confidence.toLowerCase()}</Badge>
            <Cites ids={output.likelyCause.evidenceIds} onCite={onCite} summary={summary} />
          </p>
        ) : (
          <p className="text-ink-secondary">Not determined from the evidence.</p>
        )}
      </Section>
      {output.suggestedChecks.length > 0 && (
        <Section title="Suggested checks">
          <AiText text={output.suggestedChecks.map((c) => `- ${c}`).join('\n')} />
        </Section>
      )}
      {output.uncertainty && (
        <Section title="Uncertainty">
          <p className="text-ink-secondary">{output.uncertainty}</p>
        </Section>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h3 className="mb-1 text-xs font-semibold uppercase tracking-[0.08em] text-ink-secondary">{title}</h3>
      <div className="text-ink-primary">{children}</div>
    </section>
  );
}
