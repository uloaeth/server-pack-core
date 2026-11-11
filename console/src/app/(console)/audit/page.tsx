'use client';
import { useState } from 'react';
import { Download } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { useDebounced } from '@/hooks/useApi';
import { Topbar } from '@/components/topbar';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Empty, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import { dateTime } from '@/lib/format';
import type { AuditRow } from '@/lib/types';

export default function AuditPage() {
  const [q, setQ] = useState('');
  const query = useDebounced(q, 300);
  const { data, loading, error } = useApi<{ items: AuditRow[] }>(`/audit?q=${encodeURIComponent(query)}&limit=500`, { pollMs: 10_000 });

  return (
    <>
      <Topbar title="Audit trail" subtitle="every mutation, one row per action" />
      <div className="space-y-2 pt-3">
        <div className="flex items-center gap-2">
          <Input value={q} onChange={(event) => setQ(event.target.value)} placeholder="action or entity id" className="w-72" />
          <a href="/api/console/audit/export?format=csv" className="ml-auto inline-flex h-7 items-center gap-1 rounded border border-line px-2 text-2xs text-muted hover:text-ink"><Download className="h-3 w-3" /> CSV</a>
          <a href="/api/console/audit/export?format=json" className="inline-flex h-7 items-center gap-1 rounded border border-line px-2 text-2xs text-muted hover:text-ink"><Download className="h-3 w-3" /> JSON</a>
        </div>
        <Card>
          {error ? <p className="p-3 text-xs text-bad">{error.message}</p> : loading ? <SkeletonRows rows={14} cols={5} /> : (data?.items ?? []).length === 0 ? <Empty title="No audit rows" /> : (
            <Table className="max-lg:min-w-0">
              <THead><TR><TH data-col="date">Fecha</TH><TH data-col="operator">Operador</TH><TH data-col="action">Acción</TH><TH data-col="entity">Entidad</TH><TH data-col="ip">IP</TH><TH data-col="data">Datos</TH></TR></THead>
              <tbody>
                {data!.items.map((row) => (
                  <TR key={row.id}>
                    <TD data-col="date" className="num text-2xs text-muted">{dateTime(row.createdAt)}</TD>
                    <TD data-col="operator" className="truncate text-xs" title={row.operator?.email}>{row.operator?.email ?? 'agent'}</TD>
                    <TD data-col="action"><Badge tone={row.action.includes('delete') ? 'bad' : row.action.includes('login') ? 'brand' : 'default'}>{row.action}</Badge></TD>
                    <TD data-col="entity" className="font-mono text-2xs">{row.entity} {row.entityId ? `#${row.entityId.slice(0, 8)}` : ''}</TD>
                    <TD data-col="ip" className="num text-2xs">{row.ip ?? '—'}</TD>
                    <TD data-col="data" className="max-w-[420px] truncate font-mono text-2xs text-muted" title={JSON.stringify(row.data)}>{JSON.stringify(row.data)}</TD>
                  </TR>
                ))}
              </tbody>
            </Table>
          )}
        </Card>
      </div>
    </>
  );
}
