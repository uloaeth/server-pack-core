'use client';
import { useEffect, useRef, useState } from 'react';
import { ArrowDown, ArrowUp, Columns3, Radio } from 'lucide-react';
import { Dropdown, DropdownContent, DropdownItem, DropdownTrigger } from '@/components/ui/dropdown';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Empty, SkeletonRows, Table, TD, TH, THead, TR } from '@/components/ui/table';
import type { Victim } from '@/lib/types';
import { ago, flag, osIcon, osTone, short } from '@/lib/format';
import { cn } from '@/lib/cn';

export const COLUMNS = [
  { id: 'host', label: 'Equipo', sort: true, width: 'w-[170px]' },
  { id: 'user', label: 'Usuario', sort: true, width: 'w-[120px]' },
  { id: 'os', label: 'SO', sort: true, width: 'w-[110px]' },
  { id: 'version', label: 'Versión', sort: true, width: 'w-[70px]' },
  { id: 'profile', label: 'Perfil', sort: true, width: 'w-[62px]' },
  { id: 'ip', label: 'IP / país', sort: true, width: 'w-[150px]' },
  { id: 'seen', label: 'Última señal', sort: true, width: 'w-[90px]' },
  { id: 'socket', label: 'Socket', sort: true, width: 'w-[72px]' },
  { id: 'artifacts', label: 'Artefactos', sort: true, width: 'w-[80px]' },
  { id: 'group', label: 'Grupo', sort: true, width: 'w-[120px]' }
] as const;

type ColumnId = (typeof COLUMNS)[number]['id'];

export type SortState = { id: ColumnId; dir: 'asc' | 'desc' };

export const DEFAULT_COLUMNS: ColumnId[] = ['host', 'user', 'os', 'profile', 'ip', 'seen', 'socket', 'artifacts', 'group'];

const STORAGE = 'spc.victims.columns.v2';
const STORAGE_SORT = 'spc.victims.sort.v2';

export function VictimTable({
  items,
  loading,
  selected,
  sort,
  onSelect,
  onSort,
  onOpen,
  onMenu,
  onBulk
}: {
  items: Victim[];
  loading: boolean;
  selected: string[];
  sort: SortState;
  onSelect: (ids: string[]) => void;
  onSort: (sort: SortState) => void;
  onOpen: (victim: Victim) => void;
  onMenu: (victim: Victim, x: number, y: number) => void;
  onBulk: () => void;
}) {
  const [columns, setColumns] = useState<ColumnId[]>(DEFAULT_COLUMNS);
  const anchor = useRef<number | null>(null);

  useEffect(() => {
    const saved = localStorage.getItem(STORAGE);
    if (!saved) return;
    try {
      const parsed = JSON.parse(saved) as ColumnId[];
      const valid = parsed.filter((id) => COLUMNS.some((column) => column.id === id));
      if (valid.length > 0) setColumns(valid);
    } catch { /* ignore */ }
  }, []);
  useEffect(() => { localStorage.setItem(STORAGE, JSON.stringify(columns)); }, [columns]);

  function toggle(id: ColumnId) {
    setColumns((current) => current.includes(id)
      ? current.length > 2 ? current.filter((value) => value !== id) : current
      : [...COLUMNS.map((column) => column.id).filter((value) => current.includes(value) || value === id)]);
  }

  function sortBy(id: ColumnId) {
    const next = sort.id === id && sort.dir === 'asc' ? 'desc' : 'asc';
    onSort({ id, dir: next });
    localStorage.setItem(STORAGE_SORT, JSON.stringify({ id, dir: next }));
  }

  function pick(victim: Victim, index: number, event: React.MouseEvent) {
    if (event.shiftKey && anchor.current !== null) {
      const [start, end] = [Math.min(anchor.current, index), Math.max(anchor.current, index)];
      const range = items.slice(start, end + 1).map((item) => item.id);
      onSelect([...new Set([...selected, ...range])]);
    } else {
      onSelect(selected.includes(victim.id) ? selected.filter((id) => id !== victim.id) : [...selected, victim.id]);
      anchor.current = index;
    }
  }

  const all = items.length > 0 && items.every((item) => selected.includes(item.id));

  if (loading) return <SkeletonRows rows={14} cols={columns.length + 1} />;
  if (items.length === 0) return <Empty title="Ningún equipo coincide" hint="Ajusta los filtros o matricula un build nuevo. La selección y el log quedan aquí." />;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {selected.length > 0 ? (
        <div className="flex items-center gap-2 border-b border-line bg-brand/8 px-3 py-1.5 text-xs">
          <span className="font-mono text-brand">{selected.length} seleccionados</span>
          <Button size="sm" onClick={onBulk}>Acción en lote</Button>
          <Button size="sm" variant="ghost" onClick={() => onSelect([])}>limpiar</Button>
          <span className="ml-auto text-2xs text-muted">Shift+clic para rango</span>
        </div>
      ) : null}
      <div className="min-h-0 flex-1 overflow-auto">
        <Table className="min-w-[1040px] font-mono">
          <THead>
            <TR className="hover:bg-transparent">
              <TH className="w-10">
                <input
                  type="checkbox"
                  aria-label="Seleccionar todos"
                  checked={all}
                  onChange={() => onSelect(all ? [] : items.map((item) => item.id))}
                />
              </TH>
              {COLUMNS.filter((column) => columns.includes(column.id)).map((column) => (
                <TH key={column.id} className={column.width} aria-sort={sort.id === column.id ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
                  <button className="flex items-center gap-1 uppercase hover:text-ink" onClick={() => sortBy(column.id)}>
                    {column.label}
                    {sort.id === column.id ? (sort.dir === 'asc' ? <ArrowUp className="h-2.5 w-2.5" /> : <ArrowDown className="h-2.5 w-2.5" />) : null}
                  </button>
                </TH>
              ))}
              <TH className="w-10">
                <Dropdown>
                  <DropdownTrigger asChild><Button variant="ghost" size="icon" title="Columnas"><Columns3 className="h-3.5 w-3.5" /></Button></DropdownTrigger>
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
            {items.map((victim, index) => {
              const isSelected = selected.includes(victim.id);
              return (
                <TR
                  key={victim.id}
                  data-victim={victim.id}
                  data-index={index}
                  tabIndex={-1}
                  aria-selected={isSelected}
                  className={cn('h-10 cursor-default outline-none focus-visible:ring-1 focus-visible:ring-inset focus-visible:ring-brand/70', isSelected && 'bg-brand/10 hover:bg-brand/15')}
                  onClick={(event) => pick(victim, index, event)}
                  onDoubleClick={() => onOpen(victim)}
                  onContextMenu={(event) => { event.preventDefault(); onSelect(isSelected ? selected : [victim.id]); onMenu(victim, event.clientX, event.clientY); }}
                >
                  <TD className="h-10" onClick={(event) => event.stopPropagation()}>
                    <input type="checkbox" aria-label={`Seleccionar ${victim.hostname}`} checked={isSelected} onChange={() => pick(victim, index, { shiftKey: false } as React.MouseEvent)} />
                  </TD>
                  {columns.includes('host') ? <TD className="h-10"><span className="text-ink">{victim.hostname}</span><span className="ml-2 text-2xs text-muted/60">{short(victim.id, 8)}</span></TD> : null}
                  {columns.includes('user') ? <TD className="h-10 text-muted">{victim.username}</TD> : null}
                  {columns.includes('os') ? <TD className="h-10"><span className={osTone(victim.os)}>{osIcon(victim.os)} {victim.os}</span><span className="ml-1 text-2xs text-muted/60">{victim.osVersion.split('.')[0]}</span></TD> : null}
                  {columns.includes('version') ? <TD className="h-10 text-2xs text-muted">{victim.agentVersion || '—'}</TD> : null}
                  {columns.includes('profile') ? <TD className="h-10"><Badge tone={victim.profile === 'full' ? 'brand' : 'default'}>{victim.profile}</Badge></TD> : null}
                  {columns.includes('ip') ? <TD className="h-10 text-muted"><span className="mr-1">{flag(victim.countryCode)}</span>{victim.ip ?? '—'}</TD> : null}
                  {columns.includes('seen') ? <TD className={cn('h-10', victim.online ? 'text-ok' : 'text-muted')}>{ago(victim.lastHeartbeatAt)}</TD> : null}
                  {columns.includes('socket') ? (
                    <TD className="h-10">
                      {victim.socket ? <Badge tone="ok"><Radio className="h-2.5 w-2.5" />ws</Badge> : <Badge tone={victim.online ? 'warn' : 'default'}>{victim.online ? 'hb' : 'off'}</Badge>}
                    </TD>
                  ) : null}
                  {columns.includes('artifacts') ? <TD className="h-10 num text-muted">{victim._count?.artifacts ?? victim.artifacts ?? 0}</TD> : null}
                  {columns.includes('group') ? <TD className="h-10">{victim.tags.length === 0 ? <span className="text-muted/50">—</span> : <span className="flex gap-1 overflow-hidden">{victim.tags.slice(0, 2).map((tag) => <Badge key={tag}>{tag}</Badge>)}</span>}</TD> : null}
                  <TD className="h-10" />
                </TR>
              );
            })}
          </tbody>
        </Table>
      </div>
    </div>
  );
}

export function readSavedSort(): SortState {
  if (typeof window === 'undefined') return { id: 'seen', dir: 'desc' };
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_SORT) ?? '') as SortState;
    if (parsed && COLUMNS.some((column) => column.id === parsed.id)) return parsed;
  } catch { /* ignore */ }
  return { id: 'seen', dir: 'desc' };
}
