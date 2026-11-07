'use client';
import { useState } from 'react';
import { ChevronDown, ChevronRight, Download, Folder } from 'lucide-react';
import type { Artifact } from '@/lib/types';
import { bytes, dateTime } from '@/lib/format';
import { Empty } from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';

export function FileTree({ artifacts }: { artifacts: Artifact[] }) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  if (artifacts.length === 0) return <Empty title="No files" hint="Dump a vault or wait for an artifact to process." />;
  return (
    <div className="divide-y divide-line/60">
      {artifacts.map((artifact) => (
        <div key={artifact.id}>
          <button onClick={() => setOpen((value) => ({ ...value, [artifact.id]: !value[artifact.id] }))} className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-line/20">
            {open[artifact.id] ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
            <Folder className="h-3.5 w-3.5 text-brand" />
            <span className="font-mono text-xs">{artifact.module}</span>
            <Badge>{artifact.files.length} files</Badge>
            <span className="num ml-auto text-2xs text-muted">{bytes(artifact.bytes)} · {dateTime(artifact.createdAt)}</span>
          </button>
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
