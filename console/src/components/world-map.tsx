'use client';
import dynamic from 'next/dynamic';
import { useMemo } from 'react';
import Link from 'next/link';
import type { MapPoint } from '@/lib/types';
import { useApi } from '@/hooks/useApi';
import { SkeletonRows } from '@/components/ui/table';
import { cn } from '@/lib/cn';

const Leaflet = dynamic(() => import('./leaflet-map'), { ssr: false, loading: () => <div className="h-full skeleton" /> });

export function WorldMap({ onSelect, className }: { onSelect?: (point: MapPoint) => void; className?: string }) {
  const { data, loading } = useApi<{ points: MapPoint[] }>('/map', { pollMs: 20_000 });
  const points = useMemo(() => (data?.points ?? []).filter((point) => Number.isFinite(point.lat) && Number.isFinite(point.lon)), [data]);
  return (
    <div className={cn('relative h-[380px] overflow-hidden rounded-lg border border-line', className)}>
      {loading ? <SkeletonRows rows={10} /> : <Leaflet points={points} onSelect={onSelect} />}
      <div className="pointer-events-none absolute bottom-2 left-2 z-[500] flex items-center gap-3 rounded border border-line bg-panel/90 px-2 py-1 text-2xs text-muted">
        <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-ok" /> online</span>
        <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-muted" /> offline</span>
        <span className="font-mono">{points.length} geolocalizados</span>
      </div>
      {!loading && points.length === 0 ? (
        <div className="absolute inset-0 grid place-items-center bg-panel/70 text-center text-xs text-muted">
          <div>
            <p>Aún no hay equipos geolocalizados.</p>
            <Link href="/victims" className="mt-1 inline-block text-brand">Abrir lista</Link>
          </div>
        </div>
      ) : null}
    </div>
  );
}
