'use client';
import * as React from 'react';
import { cn } from '@/lib/cn';

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input
      ref={ref}
      className={cn(
        'h-8 w-full rounded-md border border-line bg-bg px-2.5 text-xs text-ink placeholder:text-muted/70 focus-ring disabled:opacity-50',
        className
      )}
      {...props}
    />
  )
);
Input.displayName = 'Input';

export const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...props }, ref) => (
    <textarea
      ref={ref}
      className={cn('min-h-20 w-full rounded-md border border-line bg-bg p-2.5 text-xs focus-ring', className)}
      {...props}
    />
  )
);
Textarea.displayName = 'Textarea';

export function Label({ className, ...props }: React.LabelHTMLAttributes<HTMLLabelElement>) {
  return <label className={cn('mb-1 block text-2xs font-medium uppercase tracking-wide text-muted', className)} {...props} />;
}

export const Select = React.forwardRef<HTMLSelectElement, React.SelectHTMLAttributes<HTMLSelectElement>>(
  ({ className, children, ...props }, ref) => (
    <select
      ref={ref}
      className={cn('h-8 rounded-md border border-line bg-bg px-2 text-xs text-ink focus-ring', className)}
      {...props}
    >
      {children}
    </select>
  )
);
Select.displayName = 'Select';

export function Switch({ checked, onChange, label }: { checked: boolean; onChange: (value: boolean) => void; label?: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className={cn('relative h-5 w-9 rounded-full border transition-colors', checked ? 'border-brand bg-brand/70' : 'border-line bg-panel')}
    >
      <span className={cn('absolute top-0.5 h-3.5 w-3.5 rounded-full bg-ink transition-transform', checked ? 'translate-x-[18px]' : 'translate-x-0.5')} />
    </button>
  );
}
