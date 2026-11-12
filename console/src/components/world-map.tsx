'use client';
import dynamic from 'next/dynamic';
import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import type { MapPoint } from '@/lib/types';
import { useApi } from '@/hooks/useApi';
import { cn } from '@/lib/cn';

const Leaflet = dynamic(() => import('./leaflet-map'), { ssr: false, loading: () => <div className="h-full skeleton" /> });

export function WorldMap({ onSelect, className }: { onSelect?: (point: MapPoint) => void; className?: string }) {
  const { data, loading } = useApi<{ points: MapPoint[] }>('/map', { pollMs: 20_000 });
  const [ready, setReady] = useState(false);

  /* Leaflet es el chunk más pesado: se monta tras el primer pintado. */
  useEffect(() => {
    const idle = window.requestIdleCallback?.(() => setReady(true), { timeout: 4000 });
    const timer = idle ? null : window.setTimeout(() => setReady(true), 1200);
    return () => {
      if (idle) window.cancelIdleCallback?.(idle);
      if (timer) window.clearTimeout(timer);
    };
  }, []);
  const points = useMemo(() => (data?.points ?? []).filter((point) => Number.isFinite(point.lat) && Number.isFinite(point.lon)), [data]);
  return (
    <div className={cn('relative h-[380px] overflow-hidden rounded-lg border border-line', className)}>
      <img src="/world.jpg" width="480" height="200" alt="" aria-hidden="true" fetchPriority="high" className="map-underlay z-[1]" />
      {loading || !ready ? (
        <div className="absolute inset-0 grid content-start gap-2 p-4" aria-hidden="true">
          {[62, 44, 71, 38, 55, 47].map((width) => <div key={width} className="skeleton h-2.5" style={{ width: `${width}%` }} />)}
        </div>
      ) : <Leaflet points={points} onSelect={onSelect} />}
      <div className="pointer-events-none absolute bottom-2 left-2 z-[500] max-md:bottom-7 flex items-center gap-3 rounded border border-line bg-panel/90 px-2 py-1 text-2xs text-muted">
        <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-ok" /> online</span>
        <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-muted" /> offline</span>
        <span className="font-mono">{points.length} geolocalizado{points.length === 1 ? '' : 's'}</span>
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
