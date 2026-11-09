import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/cn';

const badge = cva('inline-flex items-center gap-1 rounded-full border px-2 py-0.5 font-mono text-2xs uppercase tracking-wide', {
  variants: {
    tone: {
      default: 'border-line bg-panel-2 text-muted',
      brand: 'border-brand/40 bg-brand/10 text-brand',
      ok: 'border-ok/40 bg-ok/10 text-ok',
      warn: 'border-warn/40 bg-warn/10 text-warn',
      bad: 'border-bad/40 bg-bad/10 text-bad'
    }
  },
  defaultVariants: { tone: 'default' }
});

export function Badge({ className, tone, ...props }: React.HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badge>) {
  return <span className={cn(badge({ tone }), className)} {...props} />;
}
