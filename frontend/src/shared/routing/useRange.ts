import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { presetRange, type Range } from '@/shared/ui/TimeRangePicker';

/**
 * The time range kept in the URL (?from=&to=), defaulting to the last 7 days. The
 * default is fixed when the page mounts, so it does not move (and refetch) every render.
 */
export function useRangeParams(): [Range, (r: Range) => void] {
  const [params, setParams] = useSearchParams();
  const [fallback] = useState(() => presetRange(7));
  const range = { from: params.get('from') || fallback.from, to: params.get('to') || fallback.to };
  const setRange = (r: Range) => {
    const next = new URLSearchParams(params);
    next.set('from', r.from);
    next.set('to', r.to);
    next.delete('page');
    setParams(next);
  };
  return [range, setRange];
}
