import { test } from 'node:test';
import assert from 'node:assert/strict';
import { taskLinkError } from '../server/lib/tasks';

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
