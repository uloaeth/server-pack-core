import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parse } from 'yaml';

const root = join(process.cwd(), '..', 'contract');
const load = (version: string, file: string) => parse(readFileSync(join(root, version, file), 'utf8')) as unknown as Record<string, never>;

const v1 = load('v1', 'openapi.yaml') as never as {
  paths: Record<string, Record<string, unknown>>;
  components: { schemas: Record<string, { properties?: Record<string, unknown>; required?: string[] }> };
};
const v2 = load('v2', 'openapi.yaml') as unknown as typeof v1;

test('every v1 path and method exists unchanged in v2', () => {
  for (const [path, methods] of Object.entries(v1.paths)) {
    assert.ok(v2.paths[path], `missing v2 path ${path}`);
    for (const method of Object.keys(methods)) assert.ok(v2.paths[path]![method], `missing ${method} ${path}`);
  }
});

test('v2 only adds fields and never removes a v1 schema field', () => {
  for (const [name, schema] of Object.entries(v1.components.schemas)) {
    const next = v2.components.schemas[name]!;
    for (const field of Object.keys(schema.properties ?? {})) {
      assert.ok(field in (next.properties ?? {}), `${name}.${field} disappeared`);
    }
    for (const required of schema.required ?? []) assert.ok((next.required ?? []).includes(required));
  }
});

test('the normalizer treats a missing header as v1 and 2 as v2', async () => {
  const contract = await import('../server/lib/agent-contract');
  assert.equal(contract.normalizeContract(undefined), 1);
  assert.equal(contract.normalizeContract('1'), 1);
  assert.equal(contract.normalizeContract('2'), 2);
  assert.equal(contract.normalizeContract('2, 1'), 2);
});

test('declared capabilities are sanitized and intersected', async () => {
  const contract = await import('../server/lib/agent-contract');
  assert.deepEqual(contract.sanitizeDeclared(['pty', 'nope', 'pty', 'media']), ['media', 'pty']);
  // Nothing is served yet: every v2 capability must be dropped, not trusted.
  assert.deepEqual(contract.intersectCapabilities(['pty', 'media']), ['media']);
});

test('the capability gate only applies to v2 names', async () => {
  const contract = await import('../server/lib/agent-contract');
  assert.equal(contract.capabilityMissing([], 'shell', {}), null);
  assert.equal(contract.capabilityMissing([], 'pty', {}), 'pty');
  assert.equal(contract.capabilityMissing(['pty'], 'pty', {}), null);
  assert.equal(contract.capabilityMissing([], 'agent', { command: 'miner start' }), 'miner');
});
