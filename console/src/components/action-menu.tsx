'use client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { ACTIONS, GROUPS, actionDetail, actionReason, type ActionContext, type ActionDef } from '@/lib/actions';
import type { Victim } from '@/lib/types';
import { cn } from '@/lib/cn';

export function ActionMenu({
  victim,
  x,
  y,
  context,
  onPick,
  onClose
}: {
  victim: Victim;
  x: number;
  y: number;
  context: ActionContext;
  onPick: (action: ActionDef) => void;
  onClose: () => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const enabled = useMemo(
    () => ACTIONS.filter((action) => !actionReason(action, victim, context)),
    [victim, context]
  );
  const [active, setActive] = useState<string>(enabled[0]?.id ?? '');
  const [pos, setPos] = useState({ x, y });

  useEffect(() => {
    const element = box.current;
    if (!element) return;
    const rect = element.getBoundingClientRect();
    setPos({
      x: Math.min(x, window.innerWidth - rect.width - 8),
      y: Math.min(y, window.innerHeight - rect.height - 8)
    });
    element.focus();
  }, [x, y]);

  useEffect(() => {
    const onPointer = (event: PointerEvent) => {
      if (!box.current?.contains(event.target as Node)) onClose();
    };
    window.addEventListener('pointerdown', onPointer, true);
    return () => window.removeEventListener('pointerdown', onPointer, true);
  }, [onClose]);

  function move(step: number) {
    if (enabled.length === 0) return;
    const index = Math.max(0, enabled.findIndex((action) => action.id === active));
    const next = (index + step + enabled.length) % enabled.length;
    setActive(enabled[next]!.id);
  }

  function key(event: React.KeyboardEvent) {
    if (event.key === 'Escape') { event.preventDefault(); onClose(); return; }
    if (event.key === 'ArrowDown') { event.preventDefault(); move(1); return; }
    if (event.key === 'ArrowUp') { event.preventDefault(); move(-1); return; }
    if (event.key === 'Home') { event.preventDefault(); setActive(enabled[0]?.id ?? ''); return; }
    if (event.key === 'End') { event.preventDefault(); setActive(enabled.at(-1)?.id ?? ''); return; }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      const action = enabled.find((item) => item.id === active);
      if (action) onPick(action);
    }
  }

  return (
    <div
      ref={box}
      role="menu"
      aria-label={`Acciones para ${victim.hostname}`}
      tabIndex={-1}
      onKeyDown={key}
      style={{ left: pos.x, top: pos.y }}
      className="fixed z-50 grid max-h-[calc(100vh-24px)] w-[min(760px,calc(100vw-16px))] grid-cols-2 gap-x-3 overflow-y-auto rounded-lg border border-line bg-panel/98 p-2 shadow-2xl backdrop-blur md:grid-cols-3"
    >
      {GROUPS.map((group) => {
        const items = ACTIONS.filter((action) => action.group === group.id);
        if (items.length === 0) return null;
        return (
          <section key={group.id} className="mb-2 min-w-0">
            <p className="flex items-center gap-1.5 px-1.5 pb-1 text-2xs uppercase tracking-widest text-muted/70">
              <group.icon className="h-3 w-3" /> {group.label}
            </p>
            {items.map((action) => {
              const reason = actionReason(action, victim, context);
              const selected = action.id === active && !reason;
              return (
                <button
                  key={action.id}
                  role="menuitem"
                  aria-disabled={Boolean(reason)}
                  title={reason ?? `${actionDetail(action, victim)} — ${action.task ?? 'vista local'}`}
                  onMouseEnter={() => { if (!reason) setActive(action.id); }}
                  onClick={() => { if (!reason) onPick(action); }}
                  className={cn(
                    'flex w-full items-start gap-1.5 rounded px-1.5 py-1 text-left text-xs',
                    reason ? 'cursor-not-allowed text-muted/45' : 'text-muted hover:text-ink',
                    selected && 'bg-brand/15 text-ink'
                  )}
                >
                  <action.icon className="mt-0.5 h-3 w-3 shrink-0" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate">{action.label}</span>
                    <span className="block truncate font-mono text-2xs text-muted/55">{actionDetail(action, victim)}</span>
                  </span>
                  {action.destructive ? <span className="mt-1 h-1 w-1 shrink-0 rounded-full bg-bad" /> : null}
                </button>
              );
            })}
          </section>
        );
      })}
    </div>
  );
}
