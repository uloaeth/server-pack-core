'use client';
import Link from 'next/link';
import { useApi } from '@/hooks/useApi';
import { useLiveContext } from '@/components/live-provider';
import type { EventRow } from '@/lib/types';
import { ago } from '@/lib/format';
import { Badge } from '@/components/ui/badge';
import { Empty, SkeletonRows } from '@/components/ui/table';

const tone = (kind: string) => (kind.includes('seed') || kind.includes('decrypt') ? 'ok' : kind.includes('fail') ? 'bad' : kind.includes('enroll') ? 'brand' : 'default');

export function EventFeed({ kind, victim, limit = 40 }: { kind?: string; victim?: string; limit?: number }) {
  const query = new URLSearchParams();
  if (kind) query.set('kind', kind);
  if (victim) query.set('victim', victim);
  query.set('limit', String(limit));
  const { data, loading, error, refresh } = useApi<{ items: EventRow[] }>(`/events?${query}`, { pollMs: 15_000 });
  useLiveContext();
  if (loading) return <SkeletonRows rows={8} cols={3} />;
  if (error) return <p className="p-3 text-xs text-bad">{error.message}</p>;
  const items = data?.items ?? [];
  if (items.length === 0) return <Empty title="No events" hint="Agent activity, seeds and failures appear here." />;
  return (
    <ol className="divide-y divide-line/60">
      {items.map((item) => (
        <li key={item.id} className="flex items-start gap-3 px-3 py-2">
          <Badge tone={tone(item.kind)}>{item.kind}</Badge>
          <div className="min-w-0 flex-1">
            <p className="truncate font-mono text-2xs text-muted">
              {item.data.path ? String(item.data.path) : item.data.target ? String(item.data.target) : JSON.stringify(item.data).slice(0, 140)}
            </p>
            <p className="text-2xs text-muted/70">
              {item.victim ? <Link href={`/victims/${item.victim.id}`} className="text-brand">{item.victim.id}</Link> : 'system'} · {ago(item.createdAt)} ago
            </p>
          </div>
          <button onClick={refresh} className="text-2xs text-muted/50 hover:text-ink">↻</button>
        </li>
      ))}
    </ol>
  );
}
