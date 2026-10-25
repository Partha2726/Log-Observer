import { useId } from 'react';

export interface Range {
  from: string;
  to: string;
}

const PRESETS: Array<{ label: string; days: number }> = [
  { label: 'Last 24 hours', days: 1 },
  { label: 'Last 7 days', days: 7 },
  { label: 'Last 31 days', days: 31 },
];

export function presetRange(days: number, now = new Date()): Range {
  return { from: new Date(now.getTime() - days * 86_400_000).toISOString(), to: now.toISOString() };
}

function toLocalInput(iso: string): string {
  return iso ? iso.slice(0, 16) : '';
}

function fromLocalInput(value: string): string {
  return value ? new Date(`${value}:00Z`).toISOString() : '';
}

/** Time range for Elasticsearch-backed lists: presets or a custom UTC from/to (31 days at most, server-checked). */
export function TimeRangePicker({ value, onChange }: { value: Range; onChange: (r: Range) => void }) {
  const id = useId();
  return (
    <div className="flex flex-wrap items-end gap-2 text-xs">
      <label className="flex flex-col gap-1 text-ink-secondary" htmlFor={`${id}-preset`}>
        Range
        <select
          id={`${id}-preset`}
          className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-2 py-1.5 text-sm text-ink-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
          value=""
          onChange={(e) => e.target.value && onChange(presetRange(Number(e.target.value)))}
        >
          <option value="">Preset…</option>
          {PRESETS.map((p) => (
            <option key={p.days} value={p.days}>
              {p.label}
            </option>
          ))}
        </select>
      </label>
      <label className="flex flex-col gap-1 text-ink-secondary" htmlFor={`${id}-from`}>
        From (UTC)
        <input
          id={`${id}-from`}
          type="datetime-local"
          className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-2 py-1 text-sm text-ink-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
          value={toLocalInput(value.from)}
          onChange={(e) => onChange({ ...value, from: fromLocalInput(e.target.value) })}
        />
      </label>
      <label className="flex flex-col gap-1 text-ink-secondary" htmlFor={`${id}-to`}>
        To (UTC)
        <input
          id={`${id}-to`}
          type="datetime-local"
          className="rounded-sm border border-[var(--border-hairline)] bg-[var(--surface-page)] px-2 py-1 text-sm text-ink-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
          value={toLocalInput(value.to)}
          onChange={(e) => onChange({ ...value, to: fromLocalInput(e.target.value) })}
        />
      </label>
    </div>
  );
}
