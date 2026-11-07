import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isOnline, STALE_MS } from '../server/lib/presence';

const at = (offset: number) => new Date(Date.now() - offset);

test('a fresh heartbeat keeps the victim online', () => {
  assert.equal(isOnline(at(1_000), at(STALE_MS * 10)), true);
});

test('a discord-only agent stays online from its last real message', () => {
  assert.equal(isOnline(at(STALE_MS * 10), at(5_000)), true);
});

test('silence past the stale window is offline even with an old hello', () => {
  assert.equal(isOnline(at(STALE_MS + 1), at(STALE_MS + 1)), false);
});

test('invalid dates never look online', () => {
  assert.equal(isOnline('nope', 'nope'), false);
});
