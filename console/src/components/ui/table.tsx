import * as React from 'react';
import { cn } from '@/lib/cn';
import { Badge } from '@/components/ui/badge';

export function Table({ className, ...props }: React.TableHTMLAttributes<HTMLTableElement>) {
  return (
    <div className="table-wrap">
      <table className={cn('w-full border-collapse text-left text-sm', className)} {...props} />
    </div>
  );
}

export function THead({ className, ...props }: React.HTMLAttributes<HTMLTableSectionElement>) {
  return <thead className={cn('sticky top-0 z-10 border-b border-line bg-panel-2/95 text-2xs uppercase tracking-[0.12em] text-muted backdrop-blur', className)} {...props} />;
}

export function TR({ className, ...props }: React.HTMLAttributes<HTMLTableRowElement>) {
  return <tr className={cn('border-b border-line/60 last:border-0 transition-colors hover:bg-panel-2/70', className)} {...props} />;
}

export function TH({ className, ...props }: React.ThHTMLAttributes<HTMLTableCellElement>) {
  return <th className={cn('h-10 whitespace-nowrap px-3 font-medium', className)} {...props} />;
}

export function TD({ className, ...props }: React.TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={cn('h-10 whitespace-nowrap px-3 align-middle', className)} {...props} />;
}

export function Empty({ title, hint, action }: { title: string; hint?: string; action?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-16 text-center">
      <div className="grid h-10 w-10 place-items-center rounded-full border border-line text-muted">∅</div>
      <p className="text-sm text-ink">{title}</p>
      {hint ? <p className="max-w-sm text-xs text-muted">{hint}</p> : null}
      {action}
    </div>
  );
}

export function ErrorState({ title = 'No se pudo cargar', message, retry }: { title?: string; message?: string; retry?: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-14 text-center">
      <Badge tone="bad">error</Badge>
      <p className="text-sm text-ink">{title}</p>
      {message ? <p className="max-w-md break-all font-mono text-2xs text-muted">{message}</p> : null}
      {retry ? (
        <button onClick={retry} className="mt-1 rounded border border-line px-2 py-1 text-xs hover:bg-panel">
          reintentar
        </button>
      ) : null}
    </div>
  );
}

export function SkeletonRows({ rows = 8, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <div className="divide-y divide-line/70">
      {Array.from({ length: rows }).map((_, row) => (
        <div key={row} className="grid h-9 items-center gap-3 px-2" style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}>
          {Array.from({ length: cols }).map((__, col) => (
            <div key={col} className="skeleton h-3" style={{ width: `${45 + ((row * 7 + col * 13) % 45)}%` }} />
          ))}
        </div>
      ))}
    </div>
  );
}
