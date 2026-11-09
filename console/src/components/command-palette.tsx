'use client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Search } from 'lucide-react';
import { ACTIONS, actionDetail, actionReason, type ActionContext, type ActionDef } from '@/lib/actions';
import type { Victim } from '@/lib/types';
import { cn } from '@/lib/cn';

export function CommandPalette({
  victim,
  context,
  onPick,
  onClose
}: {
  victim: Victim | null;
  context: ActionContext;
  onPick: (action: ActionDef) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => { input.current?.focus(); }, []);

  const items = useMemo(() => {
    if (!victim) return ACTIONS;
    const needle = query.trim().toLowerCase();
    return ACTIONS
      .filter((action) => !needle || `${action.label} ${action.group} ${actionDetail(action, victim)}`.toLowerCase().includes(needle))
      .sort((a, b) => Number(Boolean(actionReason(a, victim, context))) - Number(Boolean(actionReason(b, victim, context))));
  }, [query, victim, context]);

  function key(event: React.KeyboardEvent) {
    if (event.key === 'Escape') { event.preventDefault(); onClose(); return; }
    if (event.key === 'ArrowDown') { event.preventDefault(); setActive((value) => Math.min(items.length - 1, value + 1)); return; }
    if (event.key === 'ArrowUp') { event.preventDefault(); setActive((value) => Math.max(0, value - 1)); return; }
    if (event.key === 'Enter') {
      event.preventDefault();
      const action = items[active];
      if (action && victim && !actionReason(action, victim, context)) onPick(action);
    }
  }

  return (
    <div className="fixed inset-0 z-50 bg-black/55 p-4 pt-[10vh]" onPointerDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div role="dialog" aria-modal="true" aria-label="Paleta de acciones" className="mx-auto flex max-h-[70vh] w-[min(680px,100%)] flex-col overflow-hidden rounded-lg border border-line bg-panel shadow-2xl">
        <div className="flex items-center gap-2 border-b border-line px-3">
          <Search className="h-4 w-4 text-muted" />
          <input
            ref={input}
            value={query}
            onChange={(event) => { setQuery(event.target.value); setActive(0); }}
            onKeyDown={key}
            placeholder={victim ? `Acción sobre ${victim.hostname}…` : 'Selecciona un equipo primero'}
            className="h-11 flex-1 border-0 bg-transparent font-mono text-xs outline-none placeholder:text-muted/60"
          />
          <span className="rounded bg-line px-1.5 py-0.5 font-mono text-2xs text-muted">Esc</span>
        </div>
        <div className="overflow-y-auto p-1">
          {!victim ? <p className="p-5 text-center text-xs text-muted">Selecciona una víctima con las flechas y pulsa Enter.</p> : null}
          {victim && items.length === 0 ? <p className="p-5 text-center text-xs text-muted">Sin coincidencias.</p> : null}
          {victim && items.map((action, index) => {
            const reason = actionReason(action, victim, context);
            return (
              <button
                key={action.id}
                disabled={Boolean(reason)}
                onMouseEnter={() => setActive(index)}
                onClick={() => onPick(action)}
                className={cn(
                  'flex w-full items-start gap-2 rounded px-2 py-1.5 text-left text-xs',
                  reason ? 'cursor-not-allowed text-muted/45' : 'text-muted hover:text-ink',
                  index === active && !reason && 'bg-brand/15 text-ink'
                )}
              >
                <action.icon className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                <span className="min-w-0 flex-1">
                  <span className="block">{action.label}</span>
                  <span className="block truncate font-mono text-2xs text-muted/55">{actionDetail(action, victim)}</span>
                </span>
                <span className="font-mono text-2xs uppercase text-muted/45">{action.group}</span>
                {reason ? <span className="text-2xs text-warn">{reason}</span> : null}
              </button>
            );
          })}
        </div>
        <p className="border-t border-line px-3 py-1.5 text-2xs text-muted">↑↓ navegar · Enter ejecutar · Ctrl+K cerrar</p>
      </div>
    </div>
  );
}
