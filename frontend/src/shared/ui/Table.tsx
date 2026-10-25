import type { HTMLAttributes, TdHTMLAttributes, ThHTMLAttributes } from 'react';
import { cn } from '@/shared/ui/cn';

export function Table({ className, ...props }: HTMLAttributes<HTMLTableElement>) {
  return (
    <div className="overflow-x-auto">
      <table className={cn('w-full text-left text-[13px] [&_tbody_tr]:transition-colors [&_tbody_tr]:duration-300 [&_tbody_tr]:ease-out [&_tbody_tr:hover]:bg-[var(--surface-hover)] motion-reduce:[&_tbody_tr]:transition-none', className)} {...props} />
    </div>
  );
}

export function Th({ className, ...props }: ThHTMLAttributes<HTMLTableCellElement>) {
  return <th className={cn('border-b border-[var(--border-hairline)] px-3 py-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-secondary', className)} {...props} />;
}

export function Td({ className, ...props }: TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={cn('border-b border-[var(--border-hairline)] px-3 py-2 align-top text-ink-primary', className)} {...props} />;
}
