'use client';
import { useState } from 'react';
import { LogOut, ShieldCheck } from 'lucide-react';
import { toast } from 'sonner';
import { useApi, mutate } from '@/hooks/useApi';
import { Topbar } from '@/components/topbar';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { dateTime } from '@/lib/format';
import { humanError } from '@/lib/errors';

type Me = { email: string; role: string; totpEnabled: boolean; lastLoginAt: string | null };
type Session = { id: string; ip: string | null; userAgent: string | null; createdAt: string; expiresAt: string; current: boolean };

export default function SettingsPage() {
  const me = useApi<Me>('/auth/me');
  const sessions = useApi<{ items: Session[] }>('/auth/sessions');
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [busy, setBusy] = useState(false);
  const [sessionBusy, setSessionBusy] = useState('');
  const bridge = useApi<{ enabled: boolean; guild: string | null; polled: number; ingested: number; error: string | null }>('/discord/status', { pollMs: 15_000 });
  const versions = useApi<{ release: string | null; agents: Array<{ version: string; count: number }>; outdated: number }>('/versions', { pollMs: 30_000 });

  async function changePassword(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await mutate('/auth/password', 'POST', { current, next });
      toast.success('Password changed — sign in again');
      location.href = '/login';
    } catch (caught) {
      toast.error(humanError(caught) || 'failed');
    } finally {
      setBusy(false);
    }
  }

  async function revoke(id: string) {
    setSessionBusy(id);
    try {
      await mutate(`/auth/sessions/${id}`, 'DELETE');
      toast.success('Session revoked');
      sessions.refresh();
    } catch (caught) {
      toast.error(humanError(caught) || 'No se pudo revocar');
    } finally {
      setSessionBusy('');
    }
  }

  return (
    <>
      <Topbar title="Ajustes" subtitle="cuenta, sesiones y postura de seguridad" />
      <div className="grid grid-cols-1 gap-3 pt-3 lg:grid-cols-2">
        <Card>
          <CardHeader><CardTitle>Cuenta</CardTitle></CardHeader>
          <CardContent className="space-y-2 text-xs">
            <div className="flex justify-between"><span className="text-muted">Correo</span><span className="font-mono">{me.data?.email}</span></div>
            <div className="flex justify-between"><span className="text-muted">Rol</span><Badge tone="brand">{me.data?.role}</Badge></div>
            <div className="flex justify-between"><span className="text-muted">TOTP</span><Badge tone={me.data?.totpEnabled ? 'ok' : 'warn'}>{me.data?.totpEnabled ? <><ShieldCheck className="h-2.5 w-2.5" /> activado</> : 'pending'}</Badge></div>
            <div className="flex justify-between"><span className="text-muted">Último acceso</span><span className="font-mono text-muted">{dateTime(me.data?.lastLoginAt)}</span></div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>Cambiar contraseña</CardTitle></CardHeader>
          <CardContent>
            <form onSubmit={changePassword} className="space-y-2">
              <div><Label htmlFor="current-password">Actual</Label><Input id="current-password" type="password" required value={current} onChange={(event) => setCurrent(event.target.value)} /></div>
              <div><Label htmlFor="next-password">Nueva (12+, mayúscula, minúscula y dígito)</Label><Input id="next-password" type="password" required value={next} onChange={(event) => setNext(event.target.value)} /></div>
              <Button type="submit" loading={busy}>Actualizar</Button>
            </form>
          </CardContent>
        </Card>
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle>Sesiones</CardTitle></CardHeader>
          <CardContent className="divide-y divide-line/60">
            {(sessions.data?.items ?? []).map((session) => (
              <div key={session.id} className="flex items-center gap-3 py-2 text-xs">
                <span className="font-mono">{session.ip ?? '—'}</span>
                <span className="truncate text-muted">{session.userAgent ?? 'unknown client'}</span>
                {session.current ? <Badge tone="ok">actual</Badge> : null}
                <span className="ml-auto text-2xs text-muted">hasta {dateTime(session.expiresAt)}</span>
                <Button size="icon" variant="ghost" title="Revocar sesión" loading={sessionBusy === session.id} disabled={sessionBusy === session.id} onClick={() => revoke(session.id)}><LogOut className="h-3 w-3" /></Button>
              </div>
            ))}
          </CardContent>
        </Card>
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle>Versiones de agente</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-3 gap-2 text-2xs text-muted">
            <div><p className="text-ink">Publicada</p><p className="font-mono">{versions.data?.release ?? 'unknown'}</p></div>
            <div><p className="text-ink">Desplegadas</p><p className="font-mono">{versions.data?.agents.map((agent) => `${agent.version}×${agent.count}`).join(' · ') || 'none'}</p></div>
            <div><p className="text-ink">Desactualizadas</p><p className={versions.data?.outdated ? 'text-warn' : ''}>{versions.data?.outdated ?? 0} agente(s)</p></div>
            {versions.data?.outdated ? <p className="col-span-3 text-warn">Hay agentes en otra versión. Actualízalos antes de confiar en las últimas correcciones de transporte.</p> : null}
          </CardContent>
        </Card>
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle>Transporte</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-3 gap-2 text-2xs text-muted">
            <div><p className="text-ink">Contrato</p><p>v1 congelado · HMAC-SHA256</p></div>
            <div><p className="text-ink">Artefactos</p><p>Deflate → AES-256-GCM · 512 KiB chunks</p></div>
            <div><p className="text-ink">Puente de Discord</p><p>{bridge.data?.enabled ? `connected · ${bridge.data.guild}` : bridge.data?.error ?? 'disabled'}</p><p className="num">{bridge.data?.polled ?? 0} msgs · {bridge.data?.ingested ?? 0} files</p></div>
          </CardContent>
        </Card>
      </div>
    </>
  );
}
