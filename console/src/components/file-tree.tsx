'use client';
import { useState } from 'react';
import { ChevronDown, ChevronRight, Download, Folder, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import type { Artifact } from '@/lib/types';
import { bytes, dateTime } from '@/lib/format';
import { Empty } from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { mutate } from '@/hooks/useApi';
import { useRole } from '@/components/live-provider';
import { humanError } from '@/lib/errors';

export function FileTree({ artifacts, onDeleted }: { artifacts: Artifact[]; onDeleted?: () => void }) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState('');
  const role = useRole();

  async function remove(artifact: Artifact) {
    if (!confirm(`Delete artifact ${artifact.module} (${bytes(artifact.bytes)})? Wallets and screenshots from it are removed too.`)) return;
    setBusy(artifact.id);
    try {
      await mutate(`/artifacts/${artifact.id}`, 'DELETE');
      toast.success('Artifact deleted');
      onDeleted?.();
    } catch (caught) {
      toast.error(humanError(caught) || 'delete failed');
    } finally {
      setBusy('');
    }
  }
  if (artifacts.length === 0) return <Empty title="No files" hint="Dump a vault or wait for an artifact to process." />;
  return (
    <div className="divide-y divide-line/60">
      {artifacts.map((artifact) => (
        <div key={artifact.id}>
          <div role="button" tabIndex={0} onClick={() => setOpen((value) => ({ ...value, [artifact.id]: !value[artifact.id] }))} className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-line/20">
            {open[artifact.id] ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
            <Folder className="h-3.5 w-3.5 text-brand" />
            <span className="font-mono text-xs">{artifact.module}</span>
            <Badge>{artifact.files.length} files</Badge>
            <span className="num ml-auto text-2xs text-muted">{bytes(artifact.bytes)} · {dateTime(artifact.createdAt)}</span>
            {role === 'admin' ? (
              <Button
                size="icon"
                variant="ghost"
                title="delete artifact"
                loading={busy === artifact.id}
                disabled={busy === artifact.id}
                onClick={(event) => { event.stopPropagation(); void remove(artifact); }}
              >
                <Trash2 className="h-3 w-3" />
              </Button>
            ) : null}
          </div>
          {open[artifact.id] ? (
            <ul className="bg-bg/40 py-1">
              {artifact.files.map((file) => (
                <li key={file.path} className="flex items-center gap-2 px-8 py-1 hover:bg-line/20">
                  <span className="flex-1 truncate font-mono text-2xs text-muted" title={file.path}>{file.path}</span>
                  <span className="num text-2xs text-muted">{bytes(file.size)}</span>
                  <a
                    className="rounded p-1 text-muted hover:text-brand"
                    href={`/api/console/artifacts/${artifact.id}/file?path=${encodeURIComponent(file.path)}`}
                    download
                  >
                    <Download className="h-3 w-3" />
                  </a>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ))}
    </div>
  );
}
