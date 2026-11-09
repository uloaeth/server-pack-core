'use client';
import { createContext, useContext, useMemo, useState } from 'react';
import { useLive, type LiveEvent } from '@/hooks/useLive';
import type { Operator } from '@/lib/types';

type LiveContext = {
  events: LiveEvent[];
  connected: boolean;
  setConnected: (value: boolean) => void;
  online: number;
  setOnline: (value: number) => void;
  role: 'admin' | 'operator' | 'viewer';
};

const Context = createContext<LiveContext | null>(null);

export function LiveProvider({ children, role }: { children: React.ReactNode; role: 'admin' | 'operator' | 'viewer' }) {
  const [events, setEvents] = useState<LiveEvent[]>([]);
  const [connected, setConnected] = useState(false);
  const [onlineIds, setOnlineIds] = useState<Set<string>>(new Set());
  useLive(
    (event) => {
      setConnected(true);
      setEvents((current) => [event, ...current].slice(0, 200));
      if (event.victimId && (event.type === 'victim.heartbeat' || event.type === 'victim.new')) {
        const status = String(event.data.status ?? (event.type === 'victim.new' ? 'online' : ''));
        setOnlineIds((current) => {
          const next = new Set(current);
          if (status === 'online') next.add(event.victimId!);
          else if (['stale', 'offline', 'revoked'].includes(status)) next.delete(event.victimId!);
          return next;
        });
      }
    },
    { toast: true, onOpen: () => setConnected(true), onClose: () => setConnected(false) }
  );
  const online = onlineIds.size;
  const setOnline = (value: number) => setOnlineIds((current) => current.size === value ? current : current);
  const value = useMemo(() => ({ events, connected, setConnected, online, setOnline, role }), [events, connected, online, role]);
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
