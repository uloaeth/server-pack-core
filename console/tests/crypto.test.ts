import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';

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
