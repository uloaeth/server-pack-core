'use client';
import { useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Input, Select } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useDebounced } from '@/hooks/useApi';
import { X } from 'lucide-react';

export function VictimFilters({ countries }: { countries: string[] }) {
  const router = useRouter();
  const params = useSearchParams();
  const [q, setQ] = useState(params.get('q') ?? '');
  const debounced = useDebounced(q, 350);
  const [os, setOs] = useState(params.get('os') ?? '');
  const [status, setStatus] = useState(params.get('status') ?? '');
  const [country, setCountry] = useState(params.get('country') ?? '');
  const [tag, setTag] = useState(params.get('tag') ?? '');

  useEffect(() => {
    const next = new URLSearchParams(params.toString());
    if (debounced) next.set('q', debounced); else next.delete('q');
    if (os) next.set('os', os); else next.delete('os');
    if (status) next.set('status', status); else next.delete('status');
    if (country) next.set('country', country); else next.delete('country');
    if (tag) next.set('tag', tag); else next.delete('tag');
    router.replace(`/victims?${next.toString()}`, { scroll: false });
  }, [debounced, os, status, country, tag]); // eslint-disable-line react-hooks/exhaustive-deps

  const active = Boolean(q || os || status || country || tag);
  return (
    <div className="flex flex-wrap items-center gap-1.5 border-b border-line px-3 py-2">
      <Input value={q} onChange={(event) => setQ(event.target.value)} placeholder="host, user, ip, tag…" className="w-56" />
      <Select value={os} onChange={(event) => setOs(event.target.value)}>
        <option value="">all OS</option><option value="windows">windows</option><option value="linux">linux</option><option value="macos">macos</option>
      </Select>
      <Select value={status} onChange={(event) => setStatus(event.target.value)}>
        <option value="">any state</option><option value="online">online</option><option value="stale">stale</option><option value="offline">offline</option><option value="revoked">revoked</option>
      </Select>
      <Select value={country} onChange={(event) => setCountry(event.target.value)}>
        <option value="">any country</option>
        {countries.map((item) => <option key={item} value={item}>{item}</option>)}
      </Select>
      <Input value={tag} onChange={(event) => setTag(event.target.value)} placeholder="tag" className="w-28" />
      {active ? (
        <Button variant="ghost" size="sm" onClick={() => { setQ(''); setOs(''); setStatus(''); setCountry(''); setTag(''); }}>
          <X className="h-3 w-3" /> clear
        </Button>
      ) : null}
      <span className="ml-auto font-mono text-2xs text-muted">filters apply live</span>
    </div>
  );
}
