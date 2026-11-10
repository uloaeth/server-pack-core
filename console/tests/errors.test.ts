import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../src/lib/api';
import { humanError } from '../src/lib/errors';

test('los errores de socket y rol se traducen al operador', () => {
  assert.match(humanError(new ApiError(409, 'agent_offline', 'victim has no control socket')), /Sin socket/);
  assert.match(humanError(new ApiError(403, 'forbidden', 'nope')), /rol/);
  assert.equal(humanError(new Error('boom')), 'boom');
});
