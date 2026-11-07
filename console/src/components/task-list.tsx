'use client';
import { useState } from 'react';
import { ChevronDown, Clock, RotateCw, XCircle } from 'lucide-react';
import { toast } from 'sonner';
import { useApi, mutate } from '@/hooks/useApi';
import { useCanWrite } from '@/components/live-provider';
import type { Task } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Empty, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import { ago, dateTime } from '@/lib/format';

const tone = (status: string) => status === 'done' ? 'ok' : status === 'error' ? 'bad' : status === 'running' ? 'warn' : status === 'cancelled' ? 'default' : 'brand';

export function TaskList({ victim, status }: { victim?: string; status?: string }) {
  const params = new URLSearchParams();
  if (victim) params.set('victim', victim);
  if (status) params.set('status', status);
  const query = params.toString();
  const [open, setOpen] = useState<string | null>(null);
  const canWrite = useCanWrite();
  const { data, loading, error, refresh } = useApi<{ items: Task[] }>(`/tasks?${query}&limit=100`, { pollMs: 6000 });

  async function reschedule(id: string, minutes: number) {
    try {
      await mutate(`/tasks/${id}/reschedule`, 'POST', { not_before: new Date(Date.now() + minutes * 60_000).toISOString() });
      refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'failed');
    }
  }

  async function action(id: string, kind: 'cancel' | 'retry') {
    try {
      await mutate(`/tasks/${id}/${kind}`, 'POST');
      refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'failed');
    }
  }

  if (loading && !data) return <SkeletonRows rows={10} cols={6} />;
  if (error) return <p className="p-3 text-xs text-bad">{error.message}</p>;
  if ((data?.items ?? []).length === 0) return <Empty title="No tasks" hint="Queue a task against this victim." />;

  return (
    <Table>
      <THead><TR><TH>Name</TH>{victim ? null : <TH>Victim</TH>}<TH>State</TH><TH>Attempts</TH><TH>Created</TH><TH className="w-24" /></TR></THead>
      <tbody>
        {data!.items.map((task) => (
          <TR key={task.id}>
            <TD>
              <button className="flex items-center gap-1 font-mono text-xs" onClick={() => setOpen(open === task.id ? null : task.id)}>
                <ChevronDown className={`h-3 w-3 transition-transform ${open === task.id ? 'rotate-180' : ''}`} />
                {task.name}
              </button>
              {open === task.id ? (
                <div className="mt-1 max-w-[520px] rounded border border-line bg-bg p-2 font-mono text-2xs text-muted">
                  <p>params: {JSON.stringify(task.params)}</p>
                  {task.error ? <p className="text-bad">error: {task.error}</p> : null}
                  {task.result ? <pre className="mt-1 whitespace-pre-wrap break-all text-ink">{JSON.stringify(task.result, null, 2).slice(0, 4000)}</pre> : null}
                </div>
              ) : null}
            </TD>
            {victim ? null : <TD className="font-mono text-2xs">{task.victim?.hostname ?? '—'}</TD>}
            <TD><Badge tone={tone(task.status)}>{task.status}</Badge></TD>
            <TD className="num text-muted">{task.attempts}</TD>
            <TD className="num text-2xs text-muted">{dateTime(task.createdAt)} <span className="text-muted/60">({ago(task.createdAt)} ago)</span></TD>
            <TD>
              <div className="flex justify-end gap-1">
                {task.status === 'queued' ? <Button size="icon" variant="ghost" title="delay 10 min" disabled={!canWrite} onClick={() => reschedule(task.id, 10)}><Clock className="h-3 w-3" /></Button> : null}
                {['queued', 'running'].includes(task.status) ? <Button size="icon" variant="ghost" title="cancel" disabled={!canWrite} onClick={() => action(task.id, 'cancel')}><XCircle className="h-3 w-3" /></Button> : null}
                {['done', 'error', 'cancelled', 'expired'].includes(task.status) ? <Button size="icon" variant="ghost" title="retry" disabled={!canWrite} onClick={() => action(task.id, 'retry')}><RotateCw className="h-3 w-3" /></Button> : null}
              </div>
            </TD>
          </TR>
        ))}
      </tbody>
    </Table>
  );
}
