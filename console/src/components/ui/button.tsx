'use client';
import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/cn';

const styles = cva(
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-lg text-xs font-semibold transition-all active:scale-[0.98] focus-ring disabled:pointer-events-none disabled:opacity-45 [&_svg]:size-3.5',
  {
    variants: {
      variant: {
        default: 'bg-gradient-to-b from-brand to-brand/85 text-white shadow-glow hover:brightness-110',
        secondary: 'border border-line bg-panel-2/80 text-ink hover:border-line hover:bg-panel-2',
        ghost: 'text-muted hover:bg-panel-2 hover:text-ink',
        destructive: 'bg-bad/15 text-bad border border-bad/40 hover:bg-bad/25',
        outline: 'border border-line text-ink hover:bg-panel'
      },
      size: {
        default: 'h-9 px-3.5',
        sm: 'h-7 px-2.5',
        icon: 'h-8 w-8',
        lg: 'h-10 px-5'
      }
    },
    defaultVariants: { variant: 'default', size: 'default' }
  }
);

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof styles> {
  loading?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, loading, disabled, children, ...props }, ref) => (
    <button ref={ref} data-xp-button={variant ?? 'default'} className={cn(styles({ variant, size }), className)} disabled={disabled || loading} {...props}>
      {loading ? <span className="h-3 w-3 animate-spin rounded-full border border-current border-t-transparent" /> : null}
      {children}
    </button>
  )
);
Button.displayName = 'Button';
