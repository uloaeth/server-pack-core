import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createCipheriv, randomBytes } from 'node:crypto';

const env = process.env as Record<string, string | undefined>;
env['NODE_ENV'] = 'test';
env['PUBLIC_URL'] = 'https://c2.example';
env['DATABASE_URL'] = 'postgresql://x:x@localhost:5432/x';
env['JWT_SECRET'] = 'j'.repeat(48);
env['MASTER_KEY'] = randomBytes(32).toString('base64');

// eslint-disable-next-line @typescript-eslint/no-var-requires
const crypto: typeof import('../server/lib/crypto') = require('../server/lib/crypto');

test('seal/open round-trips and rejects a flipped tag', () => {
  const sealed = crypto.sealSecret('device-token-value');
  assert.equal(crypto.openSecret(sealed), 'device-token-value');
  const parts = sealed.split('.');
  const tag = Buffer.from(parts[3]!, 'base64url');
  tag[0] ^= 1;
  parts[3] = tag.toString('base64url');
  assert.throws(() => crypto.openSecret(parts.join('.')));
});

test('build token derives from the secret and hash matches', () => {
  const secret = randomBytes(32).toString('base64url');
  assert.equal(crypto.buildToken(secret).length, 43);
  assert.equal(crypto.buildTokenHash(secret), crypto.sha256hex(crypto.buildToken(secret)));
});

test('victim signature accepts only the exact canonical request', () => {
  const token = randomBytes(32).toString('base64url');
  const body = Buffer.from('{"status":"ok"}');
  const ts = '1767225600';
  const nonce = 'abcdefghijklmnop';
  const canonical = crypto.victimCanonical('POST', '/api/v1/hb', ts, nonce, body);
  const signature = crypto.hmac(crypto.unb64u(token), canonical).toString('base64url');
  assert.equal(crypto.verifyVictim(token, 'POST', '/api/v1/hb', ts, nonce, body, signature), true);
  assert.equal(crypto.verifyVictim(token, 'POST', '/api/v1/hb', ts, nonce, Buffer.from('{}'), signature), false);
  assert.equal(crypto.verifyVictim(token, 'GET', '/api/v1/hb', ts, nonce, body, signature), false);
});

test('the canonical victim request has a fixed shape with no query', () => {
  const body = Buffer.from('{"status":"ok"}');
  assert.equal(
    crypto.victimCanonical('get', '/api/v1/tasks', '1767225600', 'abcdefghijklmnop', body),
    'GET\n/api/v1/tasks\n1767225600\nabcdefghijklmnop\n' + crypto.sha256hex(body) + '\n'
  );
});

test('artifact key is stable and matches the contract derivation', () => {
  const secret = randomBytes(32).toString('base64url');
  const prefix = Buffer.from('0102030405060708', 'hex');
  const a = crypto.artifactKey(secret, '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', prefix);
  const b = crypto.artifactKey(secret, '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', prefix);
  assert.deepEqual(a, b);
  assert.equal(a.length, 32);
  const other = crypto.artifactKey(secret, '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000003', prefix);
  assert.notDeepEqual(a, other);
});

test('scheme 2 separates devices with distinct salts', () => {
  const secret = randomBytes(32).toString('base64url');
  const build = '00000000-0000-4000-8000-000000000001';
  const victim = '00000000-0000-4000-8000-000000000002';
  const prefix = Buffer.from('0102030405060708', 'hex');
  const one = crypto.artifactKey(secret, build, victim, prefix, randomBytes(32), 2);
  const two = crypto.artifactKey(secret, build, victim, prefix, randomBytes(32), 2);
  assert.notDeepEqual(one, two);
  assert.throws(() => crypto.artifactKey(secret, build, victim, prefix, undefined, 2));
});

test('enrollment token never equals the artifact key', () => {
  const secret = randomBytes(32).toString('base64url');
  const build = '00000000-0000-4000-8000-000000000001';
  const token = crypto.enrollTokenV2(secret, build);
  assert.equal(token.length, 43);
  assert.equal(crypto.enrollTokenHashV2(secret, build), crypto.sha256hex(token));
  const key = crypto.artifactKey(secret, build, '00000000-0000-4000-8000-000000000002',
    Buffer.alloc(8), randomBytes(32), 2);
  assert.notEqual(token, key.toString('base64url'));
});

test('build secrets accept legacy plaintext and sealed envelopes', () => {
  const plain = randomBytes(32).toString('base64url');
  assert.equal(crypto.openBuildSecret(plain), plain);
  assert.equal(crypto.openBuildSecret(crypto.sealSecret(plain)), plain);
});

const SECRET = randomBytes(32).toString('base64url');
const BUILD = '00000000-0000-4000-8000-000000000001';
const VICTIM = '00000000-0000-4000-8000-000000000002';
const PREFIX = Buffer.from('0102030405060708', 'hex');
const SALT = randomBytes(32);

test('artifact, outbox and log keys differ for the same victim and epoch', () => {
  const artifact = crypto.artifactKey(SECRET, BUILD, VICTIM, PREFIX, SALT, 2, 1);
  const outbox = crypto.outboxKey(SECRET, BUILD, VICTIM, SALT, 1);
  const log = crypto.logKey(SECRET, BUILD, VICTIM, SALT, 1);
  assert.notDeepEqual(artifact, outbox);
  assert.notDeepEqual(artifact, log);
  assert.notDeepEqual(outbox, log);
});

test('a new epoch changes the artifact key and the probe', () => {
  const one = crypto.artifactKey(SECRET, BUILD, VICTIM, PREFIX, SALT, 2, 1);
  const two = crypto.artifactKey(SECRET, BUILD, VICTIM, PREFIX, SALT, 2, 2);
  assert.notDeepEqual(one, two);
  assert.notEqual(crypto.keyProbe(one), crypto.keyProbe(two));
  assert.notEqual(crypto.keyProbe(one), one.toString('base64url'));
});

test('scheme confusion on the same victim yields different material', () => {
  const legacy = crypto.artifactKey(SECRET, BUILD, VICTIM, PREFIX, SALT, 1, 1);
  const modern = crypto.artifactKey(SECRET, BUILD, VICTIM, PREFIX, SALT, 2, 1);
  assert.notDeepEqual(legacy, modern);
});

test('a scheme-2 epoch without salt never silently downgrades', () => {
  assert.throws(() => crypto.artifactKey(SECRET, BUILD, VICTIM, PREFIX, undefined, 2, 1));
  assert.throws(() => crypto.artifactKey(SECRET, BUILD, VICTIM, PREFIX, SALT, 2, 0));
});

test('MASTER_KEY rotation opens a payload sealed with the previous key', () => {
  const old = randomBytes(32);
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', old, nonce);
  const ct = Buffer.concat([cipher.update('legacy-row', 'utf8'), cipher.final()]);
  const sealed = `v1.${nonce.toString('base64url')}.${ct.toString('base64url')}.${cipher.getAuthTag().toString('base64url')}`;
  const previous = process.env['MASTER_KEY_OLD'];
  process.env['MASTER_KEY_OLD'] = old.toString('base64url');
  // env is parsed once: rebuild env + crypto against the rotation window.
  delete require.cache[require.resolve('../server/lib/env')];
  delete require.cache[require.resolve('../server/lib/crypto')];
  const fresh = require('../server/lib/crypto') as typeof crypto;
  assert.equal(fresh.openSecret(sealed), 'legacy-row');
  process.env['MASTER_KEY_OLD'] = previous;
  delete require.cache[require.resolve('../server/lib/env')];
  delete require.cache[require.resolve('../server/lib/crypto')];
});

test('probe detects a one-bit key change', () => {
  const key = crypto.artifactKey(SECRET, BUILD, VICTIM, PREFIX, SALT, 2, 1);
  const probe = crypto.keyProbe(key);
  const flipped = Buffer.from(key);
  flipped[0] ^= 1;
  assert.notEqual(crypto.keyProbe(flipped), probe);
});

// eslint-disable-next-line @typescript-eslint/no-var-requires
const victims: typeof import('../server/services/victim') = require('../server/services/victim');

test('enroll decisions cover every identity state', () => {
  const base = { revokedAt: null, identityState: 'confirmed', pendingTokenEnc: null };
  assert.equal(victims.enrollDecision(null, { tokenValid: false, graceHours: 24 }), 'bootstrap');
  assert.equal(victims.enrollDecision(base, { tokenValid: true, graceHours: 24 }), 'confirmed');
  assert.equal(victims.enrollDecision(base, { tokenValid: false, graceHours: 24 }), 'unverified');
  assert.equal(victims.enrollDecision(base, { tokenValid: false, graceHours: 0 }), 'replace');
  assert.equal(victims.enrollDecision({ ...base, revokedAt: new Date() }, { tokenValid: true, graceHours: 24 }), 'confirmed');
  assert.equal(victims.enrollDecision({ ...base, identityState: 'revoked' }, { tokenValid: false, graceHours: 24 }), 'bootstrap');
});

test('a pending token classifies as pending and never confirms', () => {
  const row = {
    deviceTokenEnc: crypto.sealSecret('active'),
    prevTokenEnc: null,
    prevTokenExpiresAt: null,
    pendingTokenEnc: crypto.sealSecret('pending'),
    pendingTokenExpiresAt: new Date(Date.now() + 60_000)
  };
  assert.equal(victims.classifyToken(row, 'pending'), 'pending');
  assert.equal(victims.classifyToken(row, 'active'), 'active');
  assert.equal(victims.classifyToken(row, 'nope'), null);
});

test('auth accepts the rotation window and drops expired slots', () => {
  const now = Date.now();
  const sealed = (value: string) => crypto.sealSecret(value);
  const tokens = victims.tokenWindows({
    deviceTokenEnc: sealed('active'),
    prevTokenEnc: sealed('previous'),
    prevTokenExpiresAt: new Date(now + 60_000),
    pendingTokenEnc: sealed('pending'),
    pendingTokenExpiresAt: new Date(now - 1)
  });
  assert.equal(tokens.active, 'active');
  assert.equal(tokens.previous, 'previous');
  assert.equal(tokens.pending, null);
});

test('an expired pending token is promoted, a live one is not', () => {
  const now = 1_000_000;
  const live = { identityState: 'grace', pendingTokenEnc: 'sealed', pendingTokenExpiresAt: new Date(now + 1000) };
  const dead = { ...live, pendingTokenExpiresAt: new Date(now - 1) };
  assert.equal(victims.promoteExpired(live, now), null);
  const promoted = victims.promoteExpired(dead, now);
  assert.equal(promoted?.deviceTokenEnc, 'sealed');
  assert.equal(promoted?.identityState, 'unverified');
});
