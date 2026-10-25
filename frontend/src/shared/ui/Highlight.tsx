/**
 * Renders an Elasticsearch highlight (text with <em>…</em> around matches) as plain
 * text with emphasis, never as HTML: log lines are data and may contain markup.
 */
export function Highlight({ text }: { text: string }) {
  const parts = text.split(/(<em>.*?<\/em>)/g);
  return (
    <>
      {parts.map((part, i) =>
        part.startsWith('<em>') && part.endsWith('</em>') ? (
          <mark key={i} className="rounded-sm bg-[var(--surface-hover)] px-0.5 text-status-warning">
            {part.slice(4, -5)}
          </mark>
        ) : (
          <span key={i}>{part}</span>
        ),
      )}
    </>
  );
}
