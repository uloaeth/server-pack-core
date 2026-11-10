import { test } from 'node:test';
import assert from 'node:assert/strict';
import { balanceNumber, hasBalance, secretKindFor } from '../server/services/vault';
import { balanceNumber as uiBalance } from '../src/lib/format';

const KEY = '4c16120aed3257f9f97e5976f0bb44c54ac2ae513472aa1801ae1122e866a79c';
const SEED = 'abandon ability able about above absent absorb abstract absurd abuse access accident';

test('a 64-hex value is a private key even when the label says hex', () => {
  assert.equal(secretKindFor('hex', KEY), 'private_key');
  assert.equal(secretKindFor('evm-key', KEY), 'private_key');
});

test('a mnemonic is a seed by shape and by word count', () => {
  assert.equal(secretKindFor('hex', SEED), 'seed');
  assert.equal(secretKindFor('other', 'one two three', 12), 'seed');
  assert.equal(secretKindFor('bip39', 'anything'), 'seed');
});

test('nine random words are not promoted to a seed', () => {
  assert.equal(secretKindFor('hex', 'alpha bravo charlie delta echo foxtrot golf hotel india'), 'private_key');
});

test('balances parse units and separators; zero is not a balance', () => {
  assert.equal(balanceNumber('0 ETH'), 0);
  assert.equal(balanceNumber('0.0 ETH'), 0);
  assert.equal(balanceNumber('1.5 ETH'), 1.5);
  assert.equal(balanceNumber('0,25 BTC'), 0.25);
  assert.equal(balanceNumber('<0.0001 ETH'), 0.0001);
  assert.equal(balanceNumber(null), 0);
  assert.equal(hasBalance('0 ETH'), false);
  assert.equal(hasBalance('0.0002 ETH'), true);
});

test('the panel parser agrees with the server parser', () => {
  for (const value of ['0 ETH', '1.5 ETH', '0,25 BTC', '<0.0001 ETH', '12 ETH', null]) {
    assert.equal(uiBalance(value), balanceNumber(value));
  }
});
