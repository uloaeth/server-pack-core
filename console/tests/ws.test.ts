import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { consumeTicket, issueTicket, liveTicketCount } from '../server/lib/ws-ticket';
import { buildFrame, checkHmac, checkShape, deriveKeys, escapeJson, parseFrame, sign } from '../server/lib/ws-protocol';

function ticketFor(device: string): string {
  return issueTicket(device).value;
}

test('tickets are single use and bound to the device', () => {
  const device = randomUUID();
  const value = ticketFor(device);
  const ok = consumeTicket(value, device);
  assert.equal(ok.ok, true);
  assert.equal(consumeTicket(value, device).ok, false);
  assert.equal((consumeTicket(value, device) as { reason: string }).reason, 'reused');
});

test('a foreign device burns the ticket', () => {
  const device = randomUUID();
  const value = ticketFor(device);
  const result = consumeTicket(value, randomUUID());
  assert.equal(result.ok, false);
  assert.equal((result as { reason: string }).reason, 'device');
  assert.equal(consumeTicket(value, device).ok, false);
});

test('an expired ticket is rejected with its record for the 4401 bye', () => {
  const device = randomUUID();
  const value = ticketFor(device);
  const real = Date.now;
  Date.now = () => real() + 31_000;
  try {
    const result = consumeTicket(value, device);
    assert.equal(result.ok, false);
    assert.equal((result as { reason: string }).reason, 'expired');
    assert.equal(typeof (result as { ticket?: unknown }).ticket, 'object');
  } finally {
    Date.now = real;
  }
});

test('the fifth live ticket evicts the oldest', () => {
  const device = randomUUID();
  const issued = Array.from({ length: 5 }, () => ticketFor(device));
  assert.equal(liveTicketCount(device), 4);
  const oldest = consumeTicket(issued[0]!, device);
  assert.equal(oldest.ok, false);
  assert.equal((oldest as { reason: string }).reason, 'evicted');
  assert.equal(consumeTicket(issued[4]!, device).ok, true);
});

test('unknown tickets are unknown', () => {
  assert.equal((consumeTicket(randomBytes(32).toString('base64url'), randomUUID()) as { reason: string }).reason, 'unknown');
});

test('frame HMAC covers direction, seq, nonce and payload', () => {
  const keys = deriveKeys(randomBytes(32).toString('base64url'), randomBytes(16).toString('base64url'));
  const frame = buildFrame(keys.c2a, { id: randomUUID(), seq: 7, ts: Math.floor(Date.now() / 1000), type: 'ping', nonce: randomBytes(16).toString('base64url') }, []);
  const parsed = parseFrame(Buffer.from(frame));
  assert.equal(parsed.ok, true);
  if (!parsed.ok) return;
  assert.equal(checkHmac(keys.c2a, parsed.frame), true);
  assert.equal(checkHmac(keys.a2c, parsed.frame), false);
  assert.equal(sign(keys.c2a, 7, parsed.frame.nonce, parsed.frame.payload), String(parsed.frame.json.sig));
  const tampered = parseFrame(Buffer.from(frame.replace('"seq":7', '"seq":8')));
  assert.equal(tampered.ok, true);
  if (!tampered.ok) return;
  assert.equal(tampered.frame.seq, 8);
  assert.equal(checkHmac(keys.c2a, tampered.frame), false);
});

test('frames keep the fixed key order and escape only control characters', () => {
  const keys = deriveKeys(randomBytes(32).toString('base64url'), randomBytes(16).toString('base64url'));
  const id = randomUUID();
  const nonce = randomBytes(16).toString('base64url');
  const frame = buildFrame(keys.a2c, { id, seq: 1, ts: 100, type: 'ack', nonce }, [['reply_to', id]]);
  assert.ok(frame.startsWith(`{"v":1,"id":"${id}","seq":1,"ts":100,"type":"ack","nonce":"${nonce}","reply_to":"${id}","sig":"`));
  assert.equal(escapeJson('a"b\\c\n\té'), 'a\\"b\\\\c\\n\\té');
});

test('v is checked before the HMAC and oversize before JSON', () => {
  const badVersion = parseFrame(Buffer.from('{"v":2,"id":"x"}'));
  assert.equal(badVersion.ok, false);
  if (!badVersion.ok) assert.equal(badVersion.failure.code, 4400);
  const oversize = parseFrame(Buffer.alloc(262_145, 0x20));
  assert.equal(oversize.ok, false);
  if (!oversize.ok) assert.equal(oversize.failure.reason, 'frame_too_large');
});

test('event bodies follow their kind', () => {
  const base = { v: 1, id: randomUUID(), seq: 1, ts: 100, nonce: randomBytes(16).toString('base64url'), sig: 'A'.repeat(43) };
  assert.equal(checkShape({ ...base, type: 'event', kind: 'wallet_closed', data: {}, legacy: 'x' }), null);
  assert.equal(checkShape({ ...base, type: 'event', kind: 'wallet_detected', data: { name: 'm' }, legacy: 'x' }), 'data.pid');
  assert.equal(checkShape({ ...base, type: 'ping' }), null);
  assert.equal(checkShape({ ...base, type: 'ack' }), 'reply_to');
});
