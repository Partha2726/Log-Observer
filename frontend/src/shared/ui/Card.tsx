import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '@/shared/ui/cn';

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('rounded-md border border-[var(--border-hairline)] bg-[var(--surface-panel)] transition-[background-color,border-color,box-shadow] duration-300 ease-out hover:bg-[var(--surface-raised)] hover:shadow-lg hover:shadow-black/10 motion-reduce:transition-none', className)} {...props} />;
}

export function CardHeader({ title, actions, className }: { title: ReactNode; actions?: ReactNode; className?: string }) {
  return (
    <div className={cn('flex items-center justify-between border-b border-[var(--border-hairline)] px-4 py-2.5', className)}>
      <h2 className="text-sm font-semibold text-ink-primary">{title}</h2>
      {actions}
    </div>
  );
}
