import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hasWhere, serializeVictim } from '../server/routes/victims';

test('victim rows serialize their BigInt cursors as numbers', () => {
  const row = { id: 'victim', hostname: 'host', wsSeqIn: 5n, wsSeqOut: 7n };
  const plain = serializeVictim(row);
  assert.equal(plain.wsSeqIn, 5);
  assert.equal(plain.wsSeqOut, 7);
  assert.equal(typeof plain.wsSeqIn, 'number');
  assert.doesNotThrow(() => JSON.stringify(plain));
});

test('the has filter maps to real relation predicates', () => {
  assert.deepEqual(hasWhere('wallets'), { wallets: { some: {} } });
  assert.deepEqual(hasWhere('creds'), { credentials: { some: {} } });
  assert.ok(JSON.stringify(hasWhere('seeds')).includes('secrets'));
  assert.ok(JSON.stringify(hasWhere('balances')).includes('balance'));
  assert.deepEqual(hasWhere(undefined), {});
});
