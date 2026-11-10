'use client';
import { useEffect, useRef } from 'react';
import { toast } from 'sonner';

export type LiveEvent = {
  type: string;
  victimId?: string;
  at: number;
  data: Record<string, unknown>;
};

export type LiveStatus = 'connecting' | 'open' | 'closed';

type Options = {
  toast?: boolean;
  onOpen?: () => void;
  onClose?: (code: number) => void;
  onStatus?: (status: LiveStatus, detail?: string) => void;
  onUnauthorized?: () => void;
};

const PING_MS = 20_000;
const WATCHDOG_MS = 50_000;
const TICK_MS = 5_000;
const MAX_BACKOFF_MS = 15_000;

/**
 * One console socket with application heartbeat, watchdog and backoff.
 * The protocol ping only proves the TCP peer is alive; a dead proxy can keep
 * it open, so the console also exchanges JSON `ping`/`pong` and counts *any*
 * inbound frame as liveness. 4401 means the session is gone: reconnecting
 * forever would only spin, so the caller is asked to send the user to login.
 */
export function useLive(onEvent: (event: LiveEvent) => void, options?: Options, token = 0) {
  const handler = useRef(onEvent);
  handler.current = onEvent;
  const opts = useRef(options);
  opts.current = options;

  useEffect(() => {
    let socket: WebSocket | null = null;
    let reconnect: ReturnType<typeof setTimeout> | null = null;
    let ping: ReturnType<typeof setInterval> | null = null;
    let tick: ReturnType<typeof setInterval> | null = null;
    let tries = 0;
    let closed = false;
    let lastFrame = Date.now();
    let unauthorized = false;

    const status = (value: LiveStatus, detail?: string) => opts.current?.onStatus?.(value, detail);

    const clearTimers = () => {
      if (ping) clearInterval(ping);
      if (tick) clearInterval(tick);
      if (reconnect) clearTimeout(reconnect);
      ping = tick = reconnect = null;
    };

    const schedule = () => {
      if (closed || unauthorized) return;
      tries += 1;
      const base = Math.min(MAX_BACKOFF_MS, 500 * 2 ** Math.min(tries, 5));
      const delay = Math.round(base * (0.75 + Math.random() * 0.5));
      reconnect = setTimeout(connect, delay);
    };

    const connect = () => {
      if (closed || unauthorized) return;
      if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;
      clearTimers();
      status('connecting');
      const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
      socket = new WebSocket(`${scheme}://${location.host}/api/console/ws`);
      lastFrame = Date.now();

      socket.onopen = () => {
        tries = 0;
        lastFrame = Date.now();
        status('open');
        opts.current?.onOpen?.();
        ping = setInterval(() => {
          try { socket?.send('{"type":"ping"}'); } catch { /* closing */ }
        }, PING_MS);
        tick = setInterval(() => {
          if (closed) return;
          if (Date.now() - lastFrame > WATCHDOG_MS) {
            try { socket?.close(4408, 'watchdog'); } catch { /* already dead */ }
          }
        }, TICK_MS);
      };

      socket.onmessage = (message) => {
        lastFrame = Date.now();
        let event: LiveEvent;
        try {
          event = JSON.parse(String(message.data)) as LiveEvent;
        } catch {
          return;
        }
        if (event.type === 'hello' || event.type === 'pong') {
          const serverAt = typeof event.data?.server_time === 'number' ? event.data.server_time : null;
          if (serverAt) lastFrame = Math.max(lastFrame, serverAt);
          return;
        }
        handler.current(event);
        if (opts.current?.toast && event.type === 'notification') {
          const title = String(event.data.title ?? 'Notificación');
          const body = String(event.data.body ?? '');
          const victim = event.data.victim ? String(event.data.victim) : '';
          const link = typeof event.data.link === 'string' ? event.data.link : '';
          toast(title, {
            description: [body, victim].filter(Boolean).join(' · '),
            action: link ? { label: 'abrir', onClick: () => { location.href = link; } } : undefined
          });
        }
      };

      socket.onerror = () => {
        // onclose always follows; nothing to do here.
      };

      socket.onclose = (event) => {
        if (ping) { clearInterval(ping); ping = null; }
        if (tick) { clearInterval(tick); tick = null; }
        socket = null;
        status('closed', event.reason || `code ${event.code}`);
        opts.current?.onClose?.(event.code);
        if (closed) return;
        if (event.code === 4401) {
          unauthorized = true;
          opts.current?.onUnauthorized?.();
          return;
        }
        schedule();
      };
    };

    const wake = () => {
      if (closed || unauthorized) return;
      if (!socket || socket.readyState > WebSocket.OPEN) connect();
    };
    const onVisible = () => { if (document.visibilityState === 'visible') wake(); };
    const onOnline = () => { tries = 0; wake(); };

    window.addEventListener('online', onOnline);
    document.addEventListener('visibilitychange', onVisible);
    connect();

    return () => {
      closed = true;
      window.removeEventListener('online', onOnline);
      document.removeEventListener('visibilitychange', onVisible);
      clearTimers();
      try { socket?.close(1000, 'unmount'); } catch { /* closed */ }
    };
  }, [token]);
}
