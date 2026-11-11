// Agente de laboratorio: handshake WS real y envío de eventos bajo demanda por HTTP local.
// Uso: npx tsx tests/lab-agent.ts
import 'dotenv/config';
import { createHash, createHmac, hkdfSync, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { PrismaClient } from '@prisma/client';
import WebSocket from 'ws';

const BASE = 'http://127.0.0.1:4000';
const TOKEN = 'lab-token-0123456789abcdef0123456789abcdef';

async function main() {
  const prisma = new PrismaClient();
  const victim = await prisma.victim.findFirstOrThrow({ where: { hostname: 'SEED-DESKTOP' } });
  await prisma.$disconnect();
  const victimId = victim.id;

  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = randomBytes(16).toString('base64url');
  const canonical = ['POST', '/api/v1/ws-ticket', ts, nonce, createHash('sha256').update(Buffer.alloc(0)).digest('hex')].join('\n') + '\n';
  const sig = createHmac('sha256', Buffer.from(TOKEN, 'base64url')).update(canonical).digest('base64url');
  const res = await fetch(`${BASE}/api/v1/ws-ticket`, { method: 'POST', headers: { 'x-victim-id': victimId, 'x-timestamp': ts, 'x-nonce': nonce, 'x-signature': sig } });
  const body = await res.json() as { ticket: string; handshake_nonce: string };
  if (!body.ticket) throw new Error(JSON.stringify(body));

  const ws = new WebSocket(`ws://127.0.0.1:4000/api/v1/ws?ticket=${encodeURIComponent(body.ticket)}&device_id=${victimId}`);
  const a2c = Buffer.from(hkdfSync('sha256', Buffer.from(body.ticket, 'base64url'), Buffer.from(body.handshake_nonce, 'base64url'), Buffer.from('spc-ws-v1/a2c'), 32));
  let seq = 0;
  let ready = false;

  function frame(type: string, fields: Record<string, unknown> = {}) {
    seq++;
    const id = randomUUID();
    const n = randomBytes(16).toString('base64url');
    const t = Math.floor(Date.now() / 1000);
    let p = `{"v":1,"id":"${id}","seq":${seq},"ts":${t},"type":"${type}","nonce":"${n}"`;
    for (const [k, v] of Object.entries(fields)) p += `,"${k}":${typeof v === 'string' ? JSON.stringify(v) : typeof v === 'object' ? JSON.stringify(v) : String(v)}`;
    const payload = `${p}}`;
    const input = Buffer.concat([Buffer.from(String(seq)), Buffer.from([0x1f]), Buffer.from(n), Buffer.from([0x1f]), Buffer.from(payload)]);
    return `${p},"sig":"${createHmac('sha256', a2c).update(input).digest('base64url')}"}`;
  }

  ws.on('message', (raw) => {
    const msg = JSON.parse(String(raw)) as { type: string; id: string; seq: number; expect_seq_in?: number };
    if (msg.type === 'hello_ok') {
      seq = Number(msg.expect_seq_in ?? 0);
      ws.send(frame('ack', { reply_to: msg.id }));
      ws.send(frame('hello', {
        hostname: 'SEED-DESKTOP', user: 'seed', os: 'windows', cpu: 'x', ram: '8 GB', gpu: 'x',
        resolution: '1920x1080', java: '17', agent: '0.3.7', public_ip: '203.0.113.7', path: 'C:/spc', first: false,
        expect_seq_in: msg.seq
      }));
      ready = true;
    }
    if (msg.type === 'bye') console.log('bye', msg);
  });
  ws.on('close', (code) => { console.log('close', code); process.exit(0); });

  createServer((req, rep) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk) => chunks.push(chunk as Buffer));
    req.on('end', () => {
      if (!ready) { rep.writeHead(503).end('not ready'); return; }
      const input = (chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}) as { kind: string; data: Record<string, unknown>; legacy?: string };
      ws.send(frame('event', { kind: input.kind, data: input.data ?? {}, legacy: input.legacy ?? '' }));
      rep.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ ok: true, seq }));
    });
  }).listen(4599, '127.0.0.1', () => console.log('lab agent ready', victimId));

  setInterval(() => { if (ws.readyState === 1) ws.send(frame('ping')); }, 20_000);
}

main().catch((error) => { console.error(error); process.exit(1); });
