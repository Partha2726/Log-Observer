import type { ButtonHTMLAttributes } from 'react';
import { cn } from '@/shared/ui/cn';

type Variant = 'primary' | 'secondary' | 'ghost';

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-primary text-[var(--surface-page)] hover:bg-[#6a9fff]',
  secondary: 'border border-[var(--border-hairline)] bg-[var(--surface-raised)] text-ink-primary hover:bg-[var(--surface-hover)]',
  ghost: 'text-ink-secondary hover:bg-[var(--surface-hover)] hover:text-ink-primary',
};

export function Button({ variant = 'primary', className, type = 'button', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      type={type}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-sm px-3 py-1.5 text-sm font-semibold transition-[background-color,color,border-color,transform] duration-300 ease-out hover:scale-[1.015] motion-reduce:transform-none motion-reduce:transition-none focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--surface-page)] disabled:opacity-50 disabled:hover:scale-100',
        VARIANTS[variant],
        className,
      )}
      {...props}
    />
  );
}
