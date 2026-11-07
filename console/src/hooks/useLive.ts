'use client';
import { useEffect, useRef } from 'react';
import { toast } from 'sonner';

export type LiveEvent = {
  type: string;
  victimId?: string;
  at: number;
  data: Record<string, unknown>;
};

export function useLive(onEvent: (event: LiveEvent) => void, options?: { toast?: boolean }) {
  const handler = useRef(onEvent);
  handler.current = onEvent;
  const toastEnabled = options?.toast ?? false;

  useEffect(() => {
    let socket: WebSocket | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let tries = 0;
    let closed = false;

    const connect = () => {
      if (closed) return;
      const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
      socket = new WebSocket(`${scheme}://${location.host}/api/console/ws`);
      socket.onopen = () => { tries = 0; };
      socket.onmessage = (message) => {
        try {
          const event = JSON.parse(String(message.data)) as LiveEvent;
          if (event.type === 'hello') return;
          handler.current(event);
          if (toastEnabled && event.type === 'notification') {
            toast(String(event.data.title ?? 'notification'), { description: String(event.data.kind ?? '') });
          }
        } catch {
          // ignore
        }
      };
      socket.onclose = () => {
        if (closed) return;
        tries++;
        timer = setTimeout(connect, Math.min(15_000, 500 * 2 ** Math.min(tries, 5)));
      };
    };
    connect();
    return () => {
      closed = true;
      if (timer) clearTimeout(timer);
      socket?.close();
    };
  }, [toastEnabled]);
}
