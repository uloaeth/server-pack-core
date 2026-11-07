'use client';
import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/cn';

const styles = cva(
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-md text-xs font-medium transition-colors focus-ring disabled:pointer-events-none disabled:opacity-45 [&_svg]:size-3.5',
  {
    variants: {
      variant: {
        default: 'bg-brand text-white hover:bg-brand/85',
        secondary: 'bg-panel text-ink border border-line hover:bg-line/60',
        ghost: 'text-muted hover:text-ink hover:bg-panel',
        destructive: 'bg-bad/15 text-bad border border-bad/40 hover:bg-bad/25',
        outline: 'border border-line text-ink hover:bg-panel'
      },
      size: {
        default: 'h-8 px-3',
        sm: 'h-7 px-2',
        icon: 'h-7 w-7',
        lg: 'h-9 px-4'
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
    <button ref={ref} className={cn(styles({ variant, size }), className)} disabled={disabled || loading} {...props}>
      {loading ? <span className="h-3 w-3 animate-spin rounded-full border border-current border-t-transparent" /> : null}
      {children}
    </button>
  )
);
Button.displayName = 'Button';
