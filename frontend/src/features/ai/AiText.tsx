import { Fragment, type ReactNode } from 'react';

/**
 * Model output as text (section 11.10, UI-009): never HTML. Only two pieces of markdown are honoured,
 * list items ("- " or "* ") and **bold**; `[ev:N]` citations become buttons that call `onCite`. Every
 * other character, angle brackets included, renders literally (React escapes it).
 */
export function AiText({ text, onCite, citable }: { text: string; onCite?: (ev: string) => void; citable?: (ev: string) => boolean }) {
  const blocks: ReactNode[] = [];
  let list: ReactNode[] = [];
  const flush = () => {
    if (list.length > 0) {
      blocks.push(
        <ul key={`ul-${blocks.length}`} className="ml-4 list-disc space-y-0.5">
          {list}
        </ul>,
      );
      list = [];
    }
  };
  text.split('\n').forEach((line, i) => {
    const item = /^\s*[-*]\s+(.*)$/.exec(line);
    if (item) {
      list.push(<li key={i}>{inline(item[1], onCite, citable)}</li>);
      return;
    }
    flush();
    if (line.trim() !== '') {
      blocks.push(<p key={i}>{inline(line, onCite, citable)}</p>);
    }
  });
  flush();
  return <div className="space-y-1.5 text-sm leading-relaxed text-ink-primary">{blocks}</div>;
}

function inline(text: string, onCite?: (ev: string) => void, citable?: (ev: string) => boolean): ReactNode[] {
  const out: ReactNode[] = [];
  const pattern = /\*\*([^*]+)\*\*|\[ev:(\d+)]/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = pattern.exec(text)) !== null) {
    if (m.index > last) {
      out.push(<Fragment key={`t${last}`}>{text.slice(last, m.index)}</Fragment>);
    }
    if (m[1] !== undefined) {
      out.push(<strong key={`b${m.index}`}>{m[1]}</strong>);
    } else {
      out.push(<CitationButton key={`c${m.index}`} ev={`ev${m[2]}`} onCite={onCite} enabled={citable ? citable(`ev${m[2]}`) : true} />);
    }
    last = m.index + m[0].length;
  }
  if (last < text.length) {
    out.push(<Fragment key={`t${last}`}>{text.slice(last)}</Fragment>);
  }
  return out;
}

export function CitationButton({ ev, onCite, enabled = true }: { ev: string; onCite?: (ev: string) => void; enabled?: boolean }) {
  return (
    <button
      type="button"
      className="mx-0.5 rounded-sm border border-[var(--accent)] bg-[var(--surface-raised)] px-1 font-mono text-[11px] text-primary hover:bg-[var(--surface-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)] disabled:opacity-50"
      onClick={() => onCite?.(ev)}
      disabled={!enabled || !onCite}
      aria-label={`Show evidence ${ev}`}
    >
      {ev}
    </button>
  );
}
