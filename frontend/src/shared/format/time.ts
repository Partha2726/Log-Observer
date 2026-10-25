/**
 * Times are shown in the log timezone with its label; the UTC value is the tooltip
 * (section 12.6). The zone comes from VITE_LOG_TIMEZONE (the stack passes LOG_TIMEZONE).
 */
export const LOG_TIMEZONE: string = (import.meta.env.VITE_LOG_TIMEZONE as string | undefined) || 'UTC';

const formatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: LOG_TIMEZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  fractionalSecondDigits: 3,
  hour12: false,
});

export function formatTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  return `${formatter.format(new Date(iso)).replace(',', '')} ${LOG_TIMEZONE}`;
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) {
    return '—';
  }
  if (ms < 1000) {
    return `${ms} ms`;
  }
  const s = ms / 1000;
  if (s < 120) {
    return `${s.toFixed(1)} s`;
  }
  const m = s / 60;
  return m < 120 ? `${m.toFixed(1)} min` : `${(m / 60).toFixed(1)} h`;
}

/** The default search window: the last 7 days, as the API does. */
export function lastDays(days: number): { from: string; to: string } {
  const to = new Date();
  const from = new Date(to.getTime() - days * 86_400_000);
  return { from: from.toISOString(), to: to.toISOString() };
}
