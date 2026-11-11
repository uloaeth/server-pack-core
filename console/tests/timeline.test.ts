import { test } from 'node:test';
import assert from 'node:assert/strict';
import { patterns } from '../server/lib/timeline';

test('detects the sensitive patterns the panel highlights', () => {
  assert.ok(patterns('0x71C7656EC7ab88b098defB751B7401B5f6d8976F').includes('wallet-evm'));
  assert.deepEqual(patterns('user@example.com'), ['email']);
  assert.ok(patterns('abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about').includes('seed'));
  assert.ok(patterns('4242424242424242').includes('card'));
});

test('does not label a timestamp as a card', () => {
  assert.equal(patterns('clipboard-hola-1794406899524').includes('card'), false);
});
