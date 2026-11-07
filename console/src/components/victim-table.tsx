'use client';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Columns3 } from 'lucide-react';
import { Dropdown, DropdownContent, DropdownItem, DropdownTrigger } from '@/components/ui/dropdown';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Empty, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import type { Victim } from '@/lib/types';
import { ago, flag, osTone, short } from '@/lib/format';
import { cn } from '@/lib/cn';

export const COLUMNS = [
  { id: 'status', label: 'State' },
  { id: 'host', label: 'Host' },
  { id: 'os', label: 'OS' },
  { id: 'country', label: 'Country' },
  { id: 'user', label: 'User' },
  { id: 'ip', label: 'IP' },
  { id: 'wallets', label: 'Wallets' },
  { id: 'tags', label: 'Tags' },
  { id: 'build', label: 'Build' },
  { id: 'version', label: 'Agent' },
  { id: 'seen', label: 'Last seen' }
] as const;

const DEFAULT = ['status', 'host', 'os', 'country', 'user', 'wallets', 'tags', 'seen'];

export function VictimTable({
  items,
  loading,
  selected,
  onSelect,
  onBulk,
  linkedOnly = false
}: {
  items: Victim[];
  loading: boolean;
  selected: string[];
  onSelect: (ids: string[]) => void;
  onBulk: () => void;
  linkedOnly?: boolean;
}) {
  const router = useRouter();
  const [columns, setColumns] = useState<string[]>(DEFAULT);

  useEffect(() => {
    const saved = localStorage.getItem('spc.columns.victims');
    if (saved) {
      try { setColumns(JSON.parse(saved)); } catch { /* ignore */ }
    }
  }, []);
  useEffect(() => { localStorage.setItem('spc.columns.victims', JSON.stringify(columns)); }, [columns]);

  const toggle = (id: string) => setColumns((current) => current.includes(id) ? current.filter((value) => value !== id) : [...current, id]);
  const selectable = linkedOnly ? items.filter((item) => item.discordChannel || item.discord) : items;
  const all = selectable.length > 0 && selectable.every((item) => selected.includes(item.id));

  if (loading) return <SkeletonRows rows={12} cols={columns.length} />;
  if (items.length === 0) return <Empty title="No victims match" hint="Adjust filters or enroll a new build." />;

  return (
    <div>
      {selected.length > 0 ? (
        <div className="flex items-center gap-2 border-b border-line bg-brand/5 px-3 py-1.5 text-xs">
          <span className="font-mono">{selected.length} selected</span>
          <Button size="sm" onClick={onBulk}>Queue task</Button>
          <Button size="sm" variant="ghost" onClick={() => onSelect([])}>clear</Button>
        </div>
      ) : null}
      <Table>
        <THead>
          <TR>
            <TH className="w-8"><input type="checkbox" checked={all} onChange={() => onSelect(all ? [] : selectable.map((item) => item.id))} /></TH>
            {COLUMNS.filter((column) => columns.includes(column.id)).map((column) => <TH key={column.id}>{column.label}</TH>)}
            <TH className="w-8">
              <Dropdown>
                <DropdownTrigger asChild><Button variant="ghost" size="icon"><Columns3 className="h-3.5 w-3.5" /></Button></DropdownTrigger>
                <DropdownContent align="end">
                  {COLUMNS.map((column) => (
                    <DropdownItem key={column.id} onSelect={(event) => { event.preventDefault(); toggle(column.id); }}>
                      <input type="checkbox" checked={columns.includes(column.id)} readOnly /> {column.label}
                    </DropdownItem>
                  ))}
                </DropdownContent>
              </Dropdown>
            </TH>
          </TR>
        </THead>
        <tbody>
          {items.map((victim) => (
            <TR key={victim.id} className="cursor-pointer" onClick={() => router.push(`/victims/${victim.id}`)}>
              <TD onClick={(event) => event.stopPropagation()}>
                <input
                  type="checkbox"
                  title={linkedOnly && !(victim.discordChannel || victim.discord) ? 'no Discord link: task would fail' : undefined}
                  disabled={linkedOnly && !(victim.discordChannel || victim.discord)}
                  checked={selected.includes(victim.id)}
                  onChange={() => onSelect(selected.includes(victim.id) ? selected.filter((id) => id !== victim.id) : [...selected, victim.id])}
                />
              </TD>
              {columns.includes('status') ? <TD><Badge tone={victim.online ? 'ok' : victim.status === 'revoked' ? 'bad' : 'default'}>{victim.online ? 'online' : victim.status}</Badge></TD> : null}
              {columns.includes('host') ? <TD><span className="font-mono text-xs text-ink">{victim.hostname}</span><span className="ml-2 font-mono text-2xs text-muted/60">{short(victim.id, 8)}</span></TD> : null}
              {columns.includes('os') ? <TD><span className={osTone(victim.os)}>{victim.os}</span> <span className="text-2xs text-muted">{victim.osVersion.split('.')[0]}</span></TD> : null}
              {columns.includes('country') ? <TD className="text-muted">{flag(victim.countryCode)} {victim.country ?? '—'}</TD> : null}
              {columns.includes('user') ? <TD className="font-mono text-muted">{victim.username}</TD> : null}
              {columns.includes('ip') ? <TD className="num text-muted">{victim.ip ?? '—'}</TD> : null}
              {columns.includes('wallets') ? <TD><Badge tone={(victim.wallets ?? 0) > 0 ? 'brand' : 'default'}>{victim.wallets ?? 0}</Badge></TD> : null}
              {columns.includes('tags') ? <TD className="space-x-1">{victim.tags.slice(0, 4).map((tag) => <Badge key={tag}>{tag}</Badge>)}</TD> : null}
              {columns.includes('build') ? <TD className="text-2xs text-muted">{victim.build?.version ?? '—'}</TD> : null}
              {columns.includes('version') ? <TD className="font-mono text-2xs text-muted">{victim.agentVersion || '—'}</TD> : null}
              {columns.includes('seen') ? <TD className={cn('num text-muted', victim.online && 'text-ok')}>{ago(victim.lastHeartbeatAt)} ago</TD> : null}
              <TD />
            </TR>
          ))}
        </tbody>
      </Table>
    </div>
  );
}
