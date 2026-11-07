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

type Me = { email: string; role: string; totpEnabled: boolean; lastLoginAt: string | null };
type Session = { id: string; ip: string | null; userAgent: string | null; createdAt: string; expiresAt: string; current: boolean };

export default function SettingsPage() {
  const me = useApi<Me>('/auth/me');
  const sessions = useApi<{ items: Session[] }>('/auth/sessions');
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [busy, setBusy] = useState(false);

  async function changePassword(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await mutate('/auth/password', 'POST', { current, next });
      toast.success('Password changed — sign in again');
      location.href = '/login';
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'failed');
    } finally {
      setBusy(false);
    }
  }

  async function revoke(id: string) {
    await mutate(`/auth/sessions/${id}`, 'DELETE');
    sessions.refresh();
  }

  return (
    <>
      <Topbar title="Settings" subtitle="account, sessions, security posture" />
      <div className="grid grid-cols-2 gap-3 pt-3">
        <Card>
          <CardHeader><CardTitle>Account</CardTitle></CardHeader>
          <CardContent className="space-y-2 text-xs">
            <div className="flex justify-between"><span className="text-muted">Email</span><span className="font-mono">{me.data?.email}</span></div>
            <div className="flex justify-between"><span className="text-muted">Role</span><Badge tone="brand">{me.data?.role}</Badge></div>
            <div className="flex justify-between"><span className="text-muted">TOTP</span><Badge tone={me.data?.totpEnabled ? 'ok' : 'warn'}>{me.data?.totpEnabled ? <><ShieldCheck className="h-2.5 w-2.5" /> enabled</> : 'pending'}</Badge></div>
            <div className="flex justify-between"><span className="text-muted">Last login</span><span className="font-mono text-muted">{dateTime(me.data?.lastLoginAt)}</span></div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>Change password</CardTitle></CardHeader>
          <CardContent>
            <form onSubmit={changePassword} className="space-y-2">
              <div><Label>Current</Label><Input type="password" required value={current} onChange={(event) => setCurrent(event.target.value)} /></div>
              <div><Label>New (12+ chars, upper, lower, digit)</Label><Input type="password" required value={next} onChange={(event) => setNext(event.target.value)} /></div>
              <Button type="submit" loading={busy}>Update</Button>
            </form>
          </CardContent>
        </Card>
        <Card className="col-span-2">
          <CardHeader><CardTitle>Sessions</CardTitle></CardHeader>
          <CardContent className="divide-y divide-line/60">
            {(sessions.data?.items ?? []).map((session) => (
              <div key={session.id} className="flex items-center gap-3 py-2 text-xs">
                <span className="font-mono">{session.ip ?? '—'}</span>
                <span className="truncate text-muted">{session.userAgent ?? 'unknown client'}</span>
                {session.current ? <Badge tone="ok">current</Badge> : null}
                <span className="ml-auto text-2xs text-muted">until {dateTime(session.expiresAt)}</span>
                <Button size="icon" variant="ghost" onClick={() => revoke(session.id)}><LogOut className="h-3 w-3" /></Button>
              </div>
            ))}
          </CardContent>
        </Card>
        <Card className="col-span-2">
          <CardHeader><CardTitle>Transport</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-3 gap-2 text-2xs text-muted">
            <div><p className="text-ink">Contract</p><p>v1 frozen · HMAC-SHA256</p></div>
            <div><p className="text-ink">Artifacts</p><p>Deflate → AES-256-GCM · 512 KiB chunks</p></div>
            <div><p className="text-ink">Presence</p><p>last heartbeat, 90 s stale window</p></div>
          </CardContent>
        </Card>
      </div>
    </>
  );
}
