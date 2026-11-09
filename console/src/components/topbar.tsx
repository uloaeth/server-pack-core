'use client';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Bell, Command, Search, WifiOff, Wifi } from 'lucide-react';
import { useApi, mutate } from '@/hooks/useApi';
import { useHotkeys } from '@/hooks/useHotkeys';
import { toast } from 'sonner';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import type { Notification, Victim } from '@/lib/types';
import { ago } from '@/lib/format';
import { useLiveContext } from '@/components/live-provider';

export function Topbar({ title, subtitle, onCommand, onSlash }: { title: string; subtitle?: string; onCommand?: () => void; onSlash?: () => void }) {
  const router = useRouter();
  const [search, setSearch] = useState(false);
  const [query, setQuery] = useState('');
  const [bell, setBell] = useState(false);
  const notifications = useApi<{ items: Notification[]; unread: number }>('/notifications?unread=1', { pollMs: 30_000 });
  const results = useApi<{ items: Victim[] }>(query.trim().length > 1 ? `/victims?q=${encodeURIComponent(query)}&limit=8` : null);
  const { connected, events } = useLiveContext();
  const bridge = useApi<{ enabled: boolean; error: string | null }>('/discord/status', { pollMs: 30_000 });

  useHotkeys({
    'mod+k': () => onCommand ? onCommand() : setSearch((value) => !value),
    '/': () => onSlash ? onSlash() : setSearch(true),
    escape: () => { setSearch(false); setBell(false); }
  });

  useEffect(() => { if (query.trim().length > 1) router.prefetch('/victims'); }, [query, router]);
  useEffect(() => {
    if (events[0]?.type === 'notification') notifications.refresh();
  }, [events[0]?.at]); // eslint-disable-line react-hooks/exhaustive-deps

  async function readAll() {
    try {
      await mutate('/notifications/read', 'POST', { ids: [] });
      notifications.refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'failed');
    }
  }

  return (
    <header className="sticky top-0 z-20 flex h-12 items-center justify-between gap-4 border-b border-line bg-bg/90 px-4 backdrop-blur">
      <div className="min-w-0">
        <h1 className="truncate text-sm font-semibold">{title}</h1>
        {subtitle ? <p className="truncate text-2xs text-muted">{subtitle}</p> : null}
      </div>

      <div className="flex items-center gap-1.5">
        <Badge tone={bridge.data?.enabled ? 'brand' : 'default'} className="gap-1" title={bridge.data?.error ?? 'Discord bridge'}>
          <span className="h-1.5 w-1.5 rounded-full bg-current" />discord
        </Badge>
        <Badge tone={connected ? 'ok' : 'bad'} className="gap-1">
          {connected ? <Wifi className="h-2.5 w-2.5" /> : <WifiOff className="h-2.5 w-2.5" />}
          {connected ? 'live' : 'reconnecting'}
        </Badge>
        <button onClick={() => setSearch(true)} className="flex h-7 items-center gap-2 rounded border border-line px-2 text-2xs text-muted hover:text-ink">
          <Search className="h-3 w-3" />
          <span className="hidden lg:inline">Search</span>
          <span className="flex items-center gap-0.5 rounded bg-line px-1 font-mono text-2xs"><Command className="h-2.5 w-2.5" />K</span>
        </button>

        <div className="relative">
          <button onClick={() => setBell((value) => !value)} className="relative grid h-7 w-7 place-items-center rounded border border-line text-muted hover:text-ink">
            <Bell className="h-3.5 w-3.5" />
            {(notifications.data?.unread ?? 0) > 0 ? (
              <span className="absolute -right-1 -top-1 grid h-4 min-w-4 place-items-center rounded-full bg-bad px-1 font-mono text-2xs text-white">
                {notifications.data?.unread}
              </span>
            ) : null}
          </button>
          {bell ? (
            <div className="absolute right-0 top-9 w-[340px] rounded-lg border border-line bg-panel shadow-2xl">
              <div className="flex items-center justify-between border-b border-line px-3 py-2">
                <p className="text-xs font-semibold">Notifications</p>
                <button onClick={readAll} className="text-2xs text-muted hover:text-ink">mark all read</button>
              </div>
              <div className="max-h-80 overflow-y-auto">
                {(notifications.data?.items ?? []).length === 0 ? (
                  <p className="p-6 text-center text-xs text-muted">Nothing unread</p>
                ) : (
                  notifications.data!.items.map((item) => (
                    <Link key={item.id} href={item.link ?? '#'} onClick={() => setBell(false)} className="block border-b border-line/60 px-3 py-2 hover:bg-line/30">
                      <p className="text-xs text-ink">{item.title}</p>
                      <p className="truncate text-2xs text-muted">{item.body}</p>
                      <p className="mt-0.5 text-2xs text-muted/70">{ago(item.createdAt)} ago</p>
                    </Link>
                  ))
                )}
              </div>
            </div>
          ) : null}
        </div>
        <span className="hidden font-mono text-2xs text-muted xl:inline">{events.length} evt</span>
      </div>

      <Dialog open={search} onOpenChange={setSearch}>
        <DialogContent className="p-0">
          <div className="flex items-center gap-2 border-b border-line px-3">
            <Search className="h-4 w-4 text-muted" />
            <Input autoFocus value={query} onChange={(event) => setQuery(event.target.value)} placeholder="hostname, user, ip, tag…" className="h-11 border-0 bg-transparent px-0 focus-visible:ring-0" />
          </div>
          <div className="max-h-80 overflow-y-auto p-1">
            {(results.data?.items ?? []).map((victim) => (
              <Link key={victim.id} href={`/victims?select=${victim.id}`} onClick={() => { setSearch(false); setQuery(''); }} className="flex items-center justify-between rounded px-2 py-2 text-xs hover:bg-line">
                <span className="font-mono">{victim.hostname}</span>
                <span className="text-muted">{victim.os} · {victim.ip ?? 'no ip'}</span>
              </Link>
            ))}
            {query.length > 1 && (results.data?.items ?? []).length === 0 && !results.loading ? (
              <p className="p-4 text-center text-xs text-muted">No victim matches</p>
            ) : null}
            {query.length <= 1 ? <p className="p-4 text-center text-2xs text-muted">Type at least two characters</p> : null}
          </div>
          <div className="border-t border-line px-3 py-2 text-2xs text-muted">Esc closes · Enter opens first result</div>
        </DialogContent>
      </Dialog>
    </header>
  );
}
