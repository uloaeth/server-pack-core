'use client';
import { useState } from 'react';
import { Plus, ShieldCheck, Trash2, UserX } from 'lucide-react';
import { toast } from 'sonner';
import { useApi, mutate } from '@/hooks/useApi';
import { Topbar } from '@/components/topbar';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { Input, Label, Select } from '@/components/ui/input';
import { Empty, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import { dateTime } from '@/lib/format';
import { humanError } from '@/lib/errors';

type Row = { id: string; email: string; role: 'admin' | 'operator' | 'viewer'; disabled: boolean; totpEnabled: boolean; lastLoginAt: string | null };

export default function OperatorsPage() {
  const { data, loading, refresh, error } = useApi<{ items: Row[] }>('/operators');
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [rowBusy, setRowBusy] = useState('');
  const [form, setForm] = useState({ email: '', password: '', role: 'viewer' });

  async function create(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await mutate('/operators', 'POST', form);
      toast.success('Operator created');
      setOpen(false);
      setForm({ email: '', password: '', role: 'viewer' });
      refresh();
    } catch (caught) {
      toast.error(humanError(caught) || 'No se pudo completar');
    } finally {
      setBusy(false);
    }
  }

  async function toggle(row: Row) {
    setRowBusy(row.id);
    try {
      await mutate(`/operators/${row.id}`, 'PATCH', { disabled: !row.disabled });
      toast.success(row.disabled ? 'Operator enabled' : 'Operator disabled');
      refresh();
    } catch (caught) {
      toast.error(humanError(caught) || 'update failed');
    } finally {
      setRowBusy('');
    }
  }

  async function remove(row: Row) {
    if (!confirm(`Delete operator ${row.email}? Sessions are revoked with it.`)) return;
    setRowBusy(row.id);
    try {
      await mutate(`/operators/${row.id}`, 'DELETE');
      toast.success('Operator deleted');
      refresh();
    } catch (caught) {
      toast.error(humanError(caught) || 'delete failed');
    } finally {
      setRowBusy('');
    }
  }

  return (
    <>
      <Topbar title="Operators" subtitle="admin only · argon2id + mandatory TOTP" />
      <div className="pt-3">
        <Card>
          <div className="flex items-center justify-between border-b border-line px-3 py-2">
            <p className="text-xs text-muted">Roles: viewer read · operator mutate · admin builds/operators</p>
            <Button size="sm" onClick={() => setOpen(true)}><Plus className="h-3 w-3" /> operator</Button>
          </div>
          {error ? <p className="p-3 text-xs text-bad">{error.message}</p> : loading ? <SkeletonRows rows={5} cols={5} /> : (data?.items ?? []).length === 0 ? <Empty title="No operators" /> : (
            <Table>
              <THead><TR><TH>Correo</TH><TH>Rol</TH><TH>TOTP</TH><TH>Último acceso</TH><TH>Estado</TH><TH><span className="sr-only">Acciones</span></TH></TR></THead>
              <tbody>
                {data!.items.map((row) => (
                  <TR key={row.id}>
                    <TD className="font-mono text-xs">{row.email}</TD>
                    <TD><Badge tone={row.role === 'admin' ? 'brand' : row.role === 'operator' ? 'ok' : 'default'}>{row.role}</Badge></TD>
                    <TD>{row.totpEnabled ? <ShieldCheck className="h-3.5 w-3.5 text-ok" /> : <Badge tone="warn">pending</Badge>}</TD>
                    <TD className="num text-2xs text-muted">{dateTime(row.lastLoginAt)}</TD>
                    <TD><Badge tone={row.disabled ? 'bad' : 'default'}>{row.disabled ? 'disabled' : 'active'}</Badge></TD>
                    <TD>
                      <div className="flex justify-end gap-1">
                        <Button size="icon" variant="ghost" title={row.disabled ? 'enable' : 'disable'} loading={rowBusy === row.id} disabled={rowBusy === row.id} onClick={() => toggle(row)}><UserX className="h-3 w-3" /></Button>
                        <Button size="icon" variant="ghost" title="delete" loading={rowBusy === row.id} disabled={rowBusy === row.id} onClick={() => remove(row)}><Trash2 className="h-3 w-3" /></Button>
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
          <DialogTitle>Nuevo operador</DialogTitle>
          <form onSubmit={create} className="mt-3 space-y-2">
            <div><Label>Email</Label><Input type="email" required value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} /></div>
            <div><Label>Password</Label><Input type="password" required value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} /></div>
            <div><Label>Role</Label><Select value={form.role} onChange={(event) => setForm({ ...form, role: event.target.value })} className="w-full"><option value="viewer">viewer</option><option value="operator">operator</option><option value="admin">admin</option></Select></div>
            <p className="text-2xs text-muted">The TOTP secret is printed once in the response; the operator must scan it on first login.</p>
            <div className="flex justify-end"><Button type="submit" loading={busy} disabled={busy}>Create</Button></div>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
