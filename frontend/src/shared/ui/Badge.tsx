import type { ReactNode } from 'react';
import { CheckCircle2, CircleDashed, CircleSlash, Loader, TriangleAlert, XCircle } from 'lucide-react';
import { cn } from '@/shared/ui/cn';

type Tone = 'green' | 'red' | 'amber' | 'slate' | 'blue' | 'violet';

const TONES: Record<Tone, string> = {
  green: 'border-[var(--status-ok)] bg-[var(--surface-raised)] text-status-ok',
  red: 'border-[var(--status-failed)] bg-[var(--surface-raised)] text-status-failed',
  amber: 'border-[var(--status-warning)] bg-[var(--surface-raised)] text-status-warning',
  slate: 'border-[var(--border-hairline)] bg-[var(--surface-raised)] text-ink-secondary',
  blue: 'border-[var(--accent)] bg-[var(--surface-raised)] text-primary',
  violet: 'border-[var(--status-rejected)] bg-[var(--surface-raised)] text-status-rejected',
};

export function Badge({ tone = 'slate', children, className, title }: { tone?: Tone; children: ReactNode; className?: string; title?: string }) {
  return (
    <span title={title} className={cn('inline-flex items-center gap-1 rounded-sm border px-1.5 py-0.5 text-[11px] font-semibold tracking-wide', TONES[tone], className)}>
      {children}
    </span>
  );
}

const STATUS: Record<string, { tone: Tone; Icon: typeof CheckCircle2 }> = {
  COMPLETED: { tone: 'green', Icon: CheckCircle2 },
  OK: { tone: 'green', Icon: CheckCircle2 },
  FAILED: { tone: 'red', Icon: XCircle },
  REJECTED: { tone: 'violet', Icon: CircleSlash },
  WARN: { tone: 'amber', Icon: TriangleAlert },
  INCOMPLETE: { tone: 'amber', Icon: Loader },
  NOT_REACHED: { tone: 'slate', Icon: CircleDashed },
};

/** Status shown with colour, an icon and its text, never colour alone (section 12.5). */
export function StatusBadge({ status }: { status: string | null | undefined }) {
  if (!status) {
    return <Badge tone="slate">not assembled</Badge>;
  }
  const s = STATUS[status] ?? { tone: 'slate' as Tone, Icon: CircleDashed };
  return (
    <Badge tone={s.tone}>
      <s.Icon className="h-3 w-3" aria-hidden />
      {status.replace('_', ' ')}
    </Badge>
  );
}
