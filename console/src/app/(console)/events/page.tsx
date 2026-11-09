'use client';
import { Suspense } from 'react';
import { Topbar } from '@/components/topbar';
import { EventFeed } from '@/components/event-feed';
import { Card } from '@/components/ui/card';
import { SkeletonRows } from '@/components/ui/table';

function EventsInner() {
  return (
    <>
      <Topbar title="Eventos" subtitle="telemetría agrupada · secretos colapsados" />
      <div className="pt-3">
        <Card><EventFeed filters limit={200} /></Card>
      </div>
    </>
  );
}

export default function EventsPage() {
  return <Suspense fallback={<div className="p-4"><SkeletonRows rows={8} cols={3} /></div>}><EventsInner /></Suspense>;
}
