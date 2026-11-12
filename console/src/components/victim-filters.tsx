'use client';
import { useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Search, X } from 'lucide-react';
import { Input, Select } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useDebounced } from '@/hooks/useApi';

export function VictimFilters({
  countries,
  builds = [],
  total
}: {
  countries: string[];
  builds?: Array<{ id: string; name: string; version: string }>;
  total?: number;
}) {
  const router = useRouter();
  const params = useSearchParams();
  const [q, setQ] = useState(params.get('q') ?? '');
  const debounced = useDebounced(q, 300);
  const [os, setOs] = useState(params.get('os') ?? '');
  const [status, setStatus] = useState(params.get('status') ?? '');
  const [country, setCountry] = useState(params.get('country') ?? '');
  const [tag, setTag] = useState(params.get('tag') ?? '');
  const [build, setBuild] = useState(params.get('build') ?? '');
  const [has, setHas] = useState(params.get('has') ?? '');
  const [more, setMore] = useState(() => Boolean(params.get('os') || params.get('tag') || params.get('build') || params.get('has')));

  useEffect(() => {
    const next = new URLSearchParams(params.toString());
    if (debounced) next.set('q', debounced); else next.delete('q');
    if (os) next.set('os', os); else next.delete('os');
    if (status) next.set('status', status); else next.delete('status');
    if (country) next.set('country', country); else next.delete('country');
    if (tag) next.set('tag', tag); else next.delete('tag');
    if (build) next.set('build', build); else next.delete('build');
    if (has) next.set('has', has); else next.delete('has');
    router.replace(`/victims?${next.toString()}`, { scroll: false });
  }, [debounced, os, status, country, tag, build, has]); // eslint-disable-line react-hooks/exhaustive-deps

  const active = Boolean(q || os || status || country || tag || build || has);
  const clear = () => { setQ(''); setOs(''); setStatus(''); setCountry(''); setTag(''); setBuild(''); setHas(''); };

  return (
    <div className="xp-filters z-10 shrink-0 grid grid-cols-2 gap-1.5 border-b border-line bg-bg px-3 py-2 sm:flex sm:flex-wrap sm:items-center lg:sticky lg:top-14 lg:flex-nowrap lg:py-1.5">
      <div className="relative col-span-2 sm:w-56">
        <Search className="pointer-events-none absolute left-2 top-1.5 h-3.5 w-3.5 text-muted/60" />
        <Input
          id="victim-search"
          value={q}
          onChange={(event) => setQ(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Escape') { setQ(''); event.currentTarget.blur(); } }}
          placeholder="equipo, usuario, IP, etiqueta…"
          className="h-7 w-full pl-7 font-mono"
          aria-label="Buscar equipos"
        />
      </div>
      <Select value={status} onChange={(event) => setStatus(event.target.value)} aria-label="Estado" className="h-7 w-full sm:w-auto">
        <option value="">cualquier estado</option>
        <option value="online">online</option>
        <option value="stale">sin señal</option>
        <option value="offline">offline</option>
        <option value="revoked">revocada</option>
      </Select>
      <Select value={os} onChange={(event) => setOs(event.target.value)} aria-label="Sistema" className={`h-7 w-full sm:w-auto ${more ? '' : 'max-sm:hidden'}`}>
        <option value="">cualquier SO</option>
        <option value="windows">windows</option>
        <option value="linux">linux</option>
        <option value="macos">macos</option>
      </Select>
      <Select value={country} onChange={(event) => setCountry(event.target.value)} aria-label="País" className="h-7 w-full sm:w-auto">
        <option value="">cualquier país</option>
        {countries.map((item) => <option key={item} value={item}>{item}</option>)}
      </Select>
      <Input value={tag} onChange={(event) => setTag(event.target.value)} placeholder="grupo/etiqueta" className={`h-7 w-full sm:w-32 ${more ? '' : 'max-sm:hidden'}`} aria-label="Etiqueta" />
      <Select value={build} onChange={(event) => setBuild(event.target.value)} aria-label="Build" className={`h-7 w-full sm:w-auto sm:max-w-[200px] ${more ? '' : 'max-sm:hidden'}`}>
        <option value="">cualquier build</option>
        {builds.map((item) => <option key={item.id} value={item.id}>{item.name} v{item.version}</option>)}
      </Select>
      <Select value={has} onChange={(event) => setHas(event.target.value)} aria-label="Material" className={`h-7 w-full sm:w-auto ${more ? '' : 'max-sm:hidden'}`}>
        <option value="">cualquier material</option>
        <option value="wallets">con wallets</option>
        <option value="seeds">con seeds</option>
        <option value="creds">con credenciales</option>
        <option value="balances">con balance</option>
      </Select>
      {active ? (
        <Button variant="ghost" size="sm" onClick={clear}><X className="h-3 w-3" /> limpiar</Button>
      ) : null}
      <Button variant="ghost" size="sm" className="col-span-2 justify-self-start sm:hidden" aria-expanded={more} onClick={() => setMore((value) => !value)}>{more ? 'Menos filtros' : 'Más filtros'}</Button>
      <span className="col-span-2 shrink-0 whitespace-nowrap font-mono text-2xs text-muted sm:ml-auto sm:col-span-1">
        {total != null ? `${total} equipos` : 'filtros en vivo'}
        <span className="hidden 2xl:inline"> · <kbd className="rounded border border-line px-1">/</kbd> buscar</span>
      </span>
    </div>
  );
}
