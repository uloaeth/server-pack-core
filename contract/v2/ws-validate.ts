// Contract validator for ws v1.2. Run with: npx tsx contract/v1/ws-validate.ts
// Uses the Ajv already vendored under console/node_modules; no new dependency.
// @ts-nocheck
import { readFileSync } from 'node:fs';
import { createHmac } from 'node:crypto';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const Ajv = require('../../console/node_modules/ajv');
const dir = new URL('.', import.meta.url).pathname;

const tasks = JSON.parse(readFileSync(`${dir}task-schemas.json`, 'utf8'));
const ws = JSON.parse(readFileSync(`${dir}ws-schemas.json`, 'utf8'));
const ajv = new Ajv({ allErrors: true, strict: false });
ajv.addSchema(tasks, 'spc:tasks:v1');
ajv.addSchema(ws);
const root = ajv.getSchema(ws.$id);

let pass = 0;
let fail = 0;
function ok(name: string, condition: boolean): void {
  if (condition) pass++;
  else {
    fail++;
    console.error(`FAIL ${name}`);
  }
}

function schema(name: string) {
  const validate = ajv.getSchema(`${ws.$id}#/definitions/${name}`);
  if (!validate) throw new Error(`missing definition ${name}`);
  return validate;
}

for (const [name, def] of Object.entries(ws.definitions)) {
  if (name === 'frames' || name === 'negative_examples') continue;
  if (!def.examples) continue;
  for (const [index, example] of def.examples.entries()) {
    ok(`def ${name}[${index}]`, schema(name)(example));
  }
}

const frames = ws.definitions.frames.examples[0];
for (const frame of frames) ok(`frame ${frame.type}`, root(frame));

const [dup, v2] = ws.definitions.negative_examples.examples[0];
ok('negative nonce is schema-valid', root(dup.frame));
ok('negative v2 is schema-invalid', !root(v2.frame));

const base = frames[0];
const clean = (extra: Record<string, unknown>) => {
  const out: Record<string, unknown> = {
    v: 1,
    id: base.id,
    seq: 1,
    ts: 1767225600,
    type: 'ping',
    nonce: base.nonce,
    sig: base.sig
  };
  Object.assign(out, extra);
  return out;
};

const ack = clean({ type: 'ack', reply_to: base.id });
const bye = clean({ type: 'bye', code: 4409, reason: 'x' });
const busy = clean({
  type: 'result',
  reply_to: base.id,
  body: { status: 'error', error: 'busy', result: {}, artifacts: [] }
});
ok('ack', root(ack));
ok('bye 4409', root(bye));
ok('result needs reply_to', !root(clean({ type: 'result', body: { status: 'ok', result: {} } })));
ok('busy result', root(busy));
ok('top-level ok rejected', !root({ ...busy, ok: false }));
ok('cmd rejects reply_to', !root({ ...base, reply_to: base.id }));
ok('cmd args missing command rejected', !root({ ...base, args: {} }));
ok('cmd agent args valid', root({ ...base, name: 'agent', args: { command: 'ops' } }));
ok('cmd scan_wallets max_files bound', !root({ ...base, name: 'scan_wallets', args: { max_files: 0 } }));

// --- stateful nonce, per direction -----------------------------------------
const seen = new Map<string, Set<string>>();
function nonceOnce(direction: string, frame: any): boolean {
  if (!seen.has(direction)) seen.set(direction, new Set());
  const set = seen.get(direction)!;
  if (set.has(frame.nonce)) return false;
  set.add(frame.nonce);
  return true;
}
ok('first nonce', nonceOnce('c2a', frames[0]));
ok('same nonce opposite direction is fine', nonceOnce('a2c', frames[0]));
ok('repeated nonce same direction rejected', !nonceOnce('c2a', dup.frame));

// --- HMAC vector ------------------------------------------------------------
const key = Buffer.from('aWxBSLLuukIwQwveTvytyqhoRprD4daFCytDfxxFZ5c', 'base64url');
const input = Buffer.from(
  '313834311f5145464351305246526b64495355704c5445314f54771f7b2276223a312c226964223a2233663063346235652d376132312d346338642d396531312d326236613864306334663737222c22736571223a313834312c227473223a313736373232353630302c2274797065223a22636d64222c226e6f6e6365223a225145464351305246526b64495355704c5445314f5477222c226e616d65223a227368656c6c222c2261726773223a7b22636f6d6d616e64223a2277686f616d69227d2c227461736b5f6964223a2239623266316133342d356336642d346537662d386139302d316232633364346535663630222c2274696d656f75745f6d73223a36353030307d',
  'hex'
);
const expected = 'Qs5y-gv3masCumW9Xa7GCJhfHac5OwLuXw3AHmp19-Y';
ok('HMAC vector', createHmac('sha256', key).update(input).digest('base64url') === expected);

// --- validation order: v before HMAC ---------------------------------------
const v2BadSig = { ...frames[0], v: 2, sig: 'A'.repeat(43) };
const order = (frame: any) => {
  if (Buffer.byteLength(JSON.stringify(frame)) > 262144) return 'size';
  let parsed: any;
  try {
    parsed = JSON.parse(JSON.stringify(frame));
  } catch {
    return 'json';
  }
  if (parsed.v !== 1) return 'v';
  return 'later';
};
ok('v fails before HMAC', order(v2BadSig) === 'v');

console.log(`${pass} passed, ${fail} failed`);
if (fail > 0) process.exit(1);
