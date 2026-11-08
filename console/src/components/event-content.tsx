'use client';
import { eventView } from '@/lib/event-view';

/** Persisted event body: legacy text exactly as produced, then the typed data. */
export function EventContent({ kind, data, legacy }: { kind: string; data: Record<string, unknown>; legacy?: string | null }) {
  const view = eventView({ kind, data, legacy });
  return (
    <div className="min-w-0 flex-1 space-y-1">
      {view.legacy ? (
        <p className="whitespace-pre-wrap break-all font-mono text-xs text-ink">{view.legacy}</p>
      ) : (
        <p className="text-xs text-ink">{view.title}</p>
      )}
      {view.fields.length > 0 ? (
        <dl className="flex flex-wrap gap-x-3 gap-y-0.5 text-2xs text-muted">
          {view.fields.map((field, index) => (
            <div key={`${field.label}-${index}`} className="flex min-w-0 gap-1">
              <dt className="shrink-0 text-muted/60">{field.label}</dt>
              <dd className="max-w-full break-all font-mono">{field.value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
    </div>
  );
}
