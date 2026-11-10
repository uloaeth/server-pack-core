'use client';
import { useState } from 'react';
import { Link2, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { useApi, mutate } from '@/hooks/useApi';
import { useRole } from '@/components/live-provider';
import { Button } from '@/components/ui/button';
import { ago } from '@/lib/format';

type Ignored = { id: string; channelId: string; reason: string; createdAt: string };

/** Channels left alone after a victim delete, with an explicit way back. */
export function IgnoredChannels({ onRelinked }: { onRelinked?: () => void }) {
  const role = useRole();
  const { data, refresh } = useApi<{ items: Ignored[] }>(role === 'admin' ? '/discord/ignored' : null, { pollMs: 30_000 });
  const [busy, setBusy] = useState('');
  const items = data?.items ?? [];
  if (role !== 'admin' || items.length === 0) return null;

  async function relink(item: Ignored) {
    setBusy(item.channelId);
    try {
      await mutate(`/discord/ignored/${item.channelId}`, 'DELETE');
      toast.success('Channel queued for re-link');
      refresh();
      onRelinked?.();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 're-link failed');
    } finally {
      setBusy('');
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-1.5 border-b border-line bg-warn/5 px-3 py-2 text-2xs">
      <span className="text-warn">Ignored channels</span>
      {items.map((item) => (
        <span key={item.channelId} className="flex items-center gap-1 rounded border border-line px-1.5 py-0.5" title={`${item.reason} · ${ago(item.createdAt)}`}>
          <Trash2 className="h-2.5 w-2.5 text-muted" />
          <span className="font-mono">{item.channelId}</span>
          <Button size="sm" variant="ghost" className="h-5 px-1" loading={busy === item.channelId} disabled={busy === item.channelId} onClick={() => relink(item)}>
            <Link2 className="h-2.5 w-2.5" /> re-link
          </Button>
        </span>
      ))}
    </div>
  );
}
