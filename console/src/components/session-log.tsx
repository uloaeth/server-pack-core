'use client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link2, Pause, Play, Trash2 } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { useLiveContext } from '@/components/live-provider';
import type { EventRow, Task, Victim } from '@/lib/types';
import { ago, dateTime } from '@/lib/format';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { EventContent } from '@/components/event-content';
import { cn } from '@/lib/cn';

type Entry =
  | { kind: 'event'; id: string; at: string; event: EventRow }
  | { kind: 'task'; id: string; at: string; task: Task };

const FILTERS = [
  { id: 'all', label: 'todo' },
  { id: 'tasks', label: 'tareas' },
  { id: 'events', label: 'eventos' },
  { id: 'secrets', label: 'secretos' },
  { id: 'errors', label: 'errores' }
] as const;

type Filter = (typeof FILTERS)[number]['id'];

const SECRET = /seed|secret|password|clipboard|wallet/;

const taskTone = (status: string) => status === 'done' ? 'ok' : status === 'error' ? 'bad' : ['running', 'queued'].includes(status) ? 'warn' : 'default';

function masked(value: string, limit = 240): string {
  return value.length > limit ? `${value.slice(0, limit)}…` : value;
}

export function SessionLog({
  victim,
  onInspect,
  onFiles
}: {
  victim: Victim | null;
  onInspect: (task: Task) => void;
  onFiles: () => void;
}) {
  const [paused, setPaused] = useState(false);
  const [filter, setFilter] = useState<Filter>('all');
  const [liveRows, setLiveRows] = useState<Entry[]>([]);
  const [pending, setPending] = useState(0);
  const buffer = useRef<Entry[]>([]);
  const scroller = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const live = useLiveContext();
  const last = live.events[0];

  const events = useApi<{ items: EventRow[] }>(victim ? `/victims/${victim.id}/events` : '/events?limit=100', { pollMs: 10_000 });
  const tasks = useApi<{ items: Task[] }>(victim ? `/tasks?victim=${victim.id}&limit=60` : '/tasks?limit=60', { pollMs: 8_000 });

  useEffect(() => {
    setLiveRows([]);
    buffer.current = [];
    setPending(0);
  }, [victim?.id]);

  useEffect(() => {
    if (!last || last.type !== 'task.update' || (victim && last.victimId && last.victimId !== victim.id)) return;
    tasks.refresh();
    events.refresh();
    const data = last.data as { id?: string; status?: string; name?: string; error?: string };
    if (data.id) {
      const row: Entry = {
        kind: 'task',
        id: `live-${data.id}`,
        at: new Date(last.at).toISOString(),
        task: { id: data.id, victimId: last.victimId ?? victim?.id ?? '', name: data.name ?? 'task', params: {}, status: (data.status as Task['status']) ?? 'running', priority: 0, timeoutSec: 0, attempts: 0, result: null, error: data.error ?? null, createdAt: new Date(last.at).toISOString(), finishedAt: null }
      };
      if (paused) { buffer.current = [row, ...buffer.current]; setPending(buffer.current.length); }
      else setLiveRows((current) => [row, ...current].slice(0, 80));
    }
  }, [last?.at]); // eslint-disable-line react-hooks/exhaustive-deps

  const entries = useMemo(() => {
    const merged: Entry[] = [
      ...(events.data?.items ?? []).map((event): Entry => ({ kind: 'event', id: `event-${event.id}`, at: event.createdAt, event })),
      ...(tasks.data?.items ?? []).map((task): Entry => ({ kind: 'task', id: `task-${task.id}`, at: task.finishedAt ?? task.createdAt, task })),
      ...liveRows
    ];
    const seen = new Set<string>();
    return merged
      .sort((a, b) => b.at.localeCompare(a.at))
      .filter((entry) => {
        if (seen.has(entry.id)) return false;
        seen.add(entry.id);
        if (filter === 'tasks') return entry.kind === 'task';
        if (filter === 'events') return entry.kind === 'event';
        if (filter === 'secrets') return (entry.kind === 'event' && SECRET.test(entry.event.kind)) || (entry.kind === 'task' && /seed|wallet|clip|harvest/.test(entry.task.name));
        if (filter === 'errors') return (entry.kind === 'task' && entry.task.status === 'error') || (entry.kind === 'event' && /fail|error/.test(entry.event.kind));
        return true;
      })
      .slice(0, 240);
  }, [events.data, tasks.data, liveRows, filter]);

  useEffect(() => {
    if (!paused && stick.current && scroller.current) scroller.current.scrollTop = 0;
  }, [entries, paused]);

  function resume() {
    setLiveRows((current) => [...buffer.current, ...current].slice(0, 80));
    buffer.current = [];
    setPending(0);
    setPaused(false);
  }

  return (
    <section className="flex h-[224px] shrink-0 flex-col border-t border-line bg-black/25" aria-label="Log de sesión">
      <div className="flex h-8 shrink-0 items-center gap-2 border-b border-line/70 px-3">
        <span className="text-2xs font-medium uppercase tracking-widest text-muted">Log de sesión</span>
        {victim ? <span className="font-mono text-2xs text-brand">{victim.hostname}</span> : <span className="text-2xs text-muted">sin selección · eventos globales</span>}
        {paused ? <Badge tone="warn">pausado{pending > 0 ? ` +${pending}` : ''}</Badge> : <span className="h-1.5 w-1.5 rounded-full bg-ok" aria-hidden />}
        <div className="ml-auto flex items-center gap-1">
          <select
            value={filter}
            onChange={(event) => setFilter(event.target.value as Filter)}
            aria-label="Filtrar log"
            className="h-6 rounded border border-line bg-bg px-1 text-2xs text-muted"
          >
            {FILTERS.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}
          </select>
          <Button size="icon" variant="ghost" title={paused ? 'Reanudar' : 'Pausar'} onClick={() => paused ? resume() : setPaused(true)}>
            {paused ? <Play className="h-3 w-3" /> : <Pause className="h-3 w-3" />}
          </Button>
          <Button size="icon" variant="ghost" title="Limpiar vista" onClick={() => { events.setData({ items: [] }); tasks.setData({ items: [] }); setLiveRows([]); }}>
            <Trash2 className="h-3 w-3" />
          </Button>
        </div>
      </div>
      <div
        ref={scroller}
        onScroll={(event) => { stick.current = event.currentTarget.scrollTop < 24; }}
        aria-live={paused ? 'off' : 'polite'}
        className="min-h-0 flex-1 overflow-y-auto font-mono text-2xs"
      >
        {entries.length === 0 ? (
          <p className="px-3 py-6 text-xs text-muted">{events.loading || tasks.loading ? 'Cargando actividad…' : 'Sin actividad todavía. Las tareas y eventos de esta sesión aparecerán aquí.'}</p>
        ) : null}
        {entries.map((entry) => entry.kind === 'event' ? (
          <div key={entry.id} className="flex items-start gap-2 border-b border-line/40 px-3 py-1 hover:bg-line/20">
            <span className="w-[54px] shrink-0 text-muted/50">{ago(entry.at)}</span>
            <Badge tone={SECRET.test(entry.event.kind) ? 'brand' : /fail|error/.test(entry.event.kind) ? 'bad' : 'default'}>{entry.event.kind}</Badge>
            <div className="min-w-0 flex-1 [&_dd]:max-w-[44ch] [&_p]:text-2xs"><EventContent kind={entry.event.kind} data={entry.event.data} legacy={entry.event.legacy} /></div>
          </div>
        ) : (
          <div key={entry.id} className={cn('flex items-start gap-2 border-b border-line/40 px-3 py-1 hover:bg-line/20', entry.task.status === 'error' && 'bg-bad/5')}>
            <span className="w-[54px] shrink-0 text-muted/50">{ago(entry.at)}</span>
            <Badge tone={taskTone(entry.task.status)}>{entry.task.status}</Badge>
            <button className="min-w-0 flex-1 text-left" onClick={() => onInspect(entry.task)}>
              <span className="text-ink">{entry.task.name}</span>
              <span className="ml-2 text-muted/60">{masked(JSON.stringify(entry.task.params))}</span>
              {entry.task.error ? <span className="ml-2 text-bad">{entry.task.error}</span> : null}
              {entry.task.status === 'done' && entry.task.result ? <span className="ml-2 text-muted/55">{masked(JSON.stringify(entry.task.result))}</span> : null}
            </button>
            {entry.task.result && 'upload_id' in entry.task.result ? (
              <button className="shrink-0 text-brand/70 hover:text-brand" title="Ver artefacto" onClick={onFiles}><Link2 className="h-3 w-3" /></button>
            ) : null}
            <button className="shrink-0 text-muted/50 hover:text-ink" title={`Abrir tarea ${entry.task.id}`} onClick={() => onInspect(entry.task)}><Link2 className="h-3 w-3" /></button>
          </div>
        ))}
        {paused ? <p className="px-3 py-2 text-2xs text-warn">Log en pausa; al reanudar se añaden {pending} entradas.</p> : null}
      </div>
      <p className="shrink-0 border-t border-line/60 px-3 py-0.5 text-2xs text-muted/50">Última señal {victim ? `${ago(victim.lastHeartbeatAt)} · ${dateTime(victim.lastHeartbeatAt)}` : '—'} · {entries.length} filas</p>
    </section>
  );
}
