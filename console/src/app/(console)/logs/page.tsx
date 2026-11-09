'use client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Download, Pause, Play, RefreshCw, WrapText } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { Topbar } from '@/components/topbar';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { Empty, ErrorState, SkeletonRows } from '@/components/ui/table';
import { bytes } from '@/lib/format';
import { cn } from '@/lib/cn';

type LogRow = { line: number; level: string; text: string };

const levelTone: Record<string, string> = {
  error: 'text-bad',
  warn: 'text-warn',
  info: 'text-muted',
  debug: 'text-muted/60'
};

export default function LogsPage() {
  const initial = typeof window === 'undefined' ? new URLSearchParams() : new URLSearchParams(window.location.search);
  const [file, setFile] = useState(initial.get('file') || 'api.log');
  const [level, setLevel] = useState(initial.get('level') ?? '');
  const [q, setQ] = useState(initial.get('q') ?? '');
  const [paused, setPaused] = useState(false);
  const [autoscroll, setAutoscroll] = useState(true);
  const [wrap, setWrap] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const files = useApi<{ items: string[] }>('/logs/files');
  const query = useMemo(() => {
    const params = new URLSearchParams({ file, limit: '500' });
    if (level) params.set('level', level);
    if (q.trim()) params.set('q', q.trim());
    return params.toString();
  }, [file, level, q]);
  const logs = useApi<{ file: string; size: number; total: number; items: LogRow[] }>(`/logs?${query}`, {
    pollMs: paused ? undefined : 3000
  });

  useEffect(() => {
    if (!autoscroll || paused) return;
    const node = box.current;
    if (node) node.scrollTop = node.scrollHeight;
  }, [logs.data?.items.length, autoscroll, paused]);

  useEffect(() => {
    const params = new URLSearchParams();
    params.set('file', file);
    if (level) params.set('level', level);
    if (q.trim()) params.set('q', q.trim());
    window.history.replaceState(null, '', `/logs?${params}`);
  }, [file, level, q]);

  const items = logs.data?.items ?? [];

  return (
    <>
      <Topbar title="Logs" subtitle="salida del API y del worker · valores sensibles redactados" />
      <div className="space-y-2 pt-3">
        <div className="flex flex-wrap items-center gap-2">
          <Select value={file} onChange={(event) => setFile(event.target.value)} aria-label="Fichero de log">
            {(files.data?.items ?? [file]).map((name) => <option key={name} value={name}>{name}</option>)}
          </Select>
          <Select value={level} onChange={(event) => setLevel(event.target.value)} aria-label="Nivel mínimo">
            <option value="">todos los niveles</option>
            <option value="error">error</option>
            <option value="warn">warn</option>
            <option value="info">info</option>
            <option value="debug">debug</option>
          </Select>
          <Input
            value={q}
            onChange={(event) => setQ(event.target.value)}
            placeholder="filtrar texto…"
            aria-label="Filtrar texto"
            className="w-56"
          />
          <Button size="sm" variant="secondary" onClick={logs.refresh}><RefreshCw className="h-3 w-3" /> refrescar</Button>
          <Button size="sm" variant={paused ? 'default' : 'secondary'} onClick={() => setPaused((value) => !value)}>
            {paused ? <Play className="h-3 w-3" /> : <Pause className="h-3 w-3" />} {paused ? 'reanudar' : 'pausar'}
          </Button>
          <Button size="sm" variant={autoscroll ? 'default' : 'secondary'} onClick={() => setAutoscroll((value) => !value)}>
            autoscroll {autoscroll ? 'on' : 'off'}
          </Button>
          <Button size="sm" variant={wrap ? 'default' : 'secondary'} onClick={() => setWrap((value) => !value)} aria-label="Ajuste de línea">
            <WrapText className="h-3 w-3" /> ajuste
          </Button>
          <a
            href={`/api/console/logs/download?${query}`}
            className="ml-auto inline-flex items-center gap-1 rounded border border-line px-2 py-1 text-2xs text-brand hover:bg-panel"
          >
            <Download className="h-3 w-3" /> descargar filtrado
          </a>
          <span className="font-mono text-2xs text-muted">{logs.data ? `${logs.data.total} líneas · ${bytes(logs.data.size)}` : ''}</span>
        </div>
        <Card className="max-h-[calc(100vh-180px)] min-h-[240px] overflow-hidden bg-black/40">
          {logs.loading && !logs.data ? <SkeletonRows rows={14} cols={1} /> : logs.error ? (
            <ErrorState title="No se pudo leer el log" message={logs.error.message} retry={logs.refresh} />
          ) : items.length === 0 ? (
            <Empty title="Sin líneas para estos filtros" hint="Prueba a quitar el nivel o el texto." />
          ) : (
            <div ref={box} className="scroll-panel h-full max-h-[calc(100vh-180px)] py-2" role="log" aria-live={paused ? 'off' : 'polite'}>
              {items.map((row) => (
                <div key={`${row.line}-${row.text.slice(0, 24)}`} className="flex gap-2 px-3 font-mono text-2xs leading-5 hover:bg-panel/60">
                  <span className="w-12 shrink-0 select-none text-right text-muted/40">{row.line}</span>
                  <span className={cn('w-10 shrink-0 uppercase', levelTone[row.level] ?? 'text-muted')}>{row.level}</span>
                  <span className={cn('min-w-0 flex-1 text-muted', wrap ? 'break-all whitespace-pre-wrap' : 'truncate')}>{row.text}</span>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
