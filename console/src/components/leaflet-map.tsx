'use client';
import { MapContainer, TileLayer, CircleMarker, Tooltip } from 'react-leaflet';
import 'leaflet/dist/leaflet.css';
import type { MapPoint } from '@/lib/types';

export default function LeafletMap({ points, onSelect }: { points: MapPoint[]; onSelect?: (point: MapPoint) => void }) {
  return (
    <MapContainer center={[28, 8]} zoom={2} minZoom={2} maxZoom={9} className="h-full w-full" worldCopyJump>
      <TileLayer
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution="&copy; OpenStreetMap"
        noWrap
        detectRetina
      />
      {points.map((point) => (
        <CircleMarker
          key={point.id}
          center={[point.lat, point.lon]}
          radius={point.online ? 5 : 3}
          pathOptions={{
            color: point.online ? '#34d399' : '#7b8494',
            fillColor: point.online ? '#34d399' : '#7b8494',
            fillOpacity: 0.55,
            weight: 1
          }}
          eventHandlers={{ click: () => onSelect?.(point) }}
        >
          <Tooltip direction="top" offset={[0, -4]} opacity={1}>
            <div className="font-mono text-2xs">
              <p className="font-semibold">{point.hostname}</p>
              <p>{point.city ? `${point.city}, ` : ''}{point.country ?? 'unknown'}</p>
              <p>{point.os}</p>
            </div>
          </Tooltip>
        </CircleMarker>
      ))}
    </MapContainer>
  );
}
