// Agente falso para capturas locales: matrícula sembrada y token del seed.
import { createHash, createHmac, hkdfSync, randomBytes, randomUUID } from 'node:crypto';
import WebSocket from 'ws';
async function main() {
  const BASE = 'http://127.0.0.1:4000';
  const victimId = '2640ea90-ba40-4850-8045-bb3a838e3835';
  const token = 'dWV3H-HsE464XW8SVMxlL1Dg3lDsc2QEDmpdWWP4j3Y';
  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = randomBytes(16).toString('base64url');
  const canonical = ['POST', '/api/v1/ws-ticket', ts, nonce, createHash('sha256').update(Buffer.alloc(0)).digest('hex')].join('\n') + '\n';
  const sig = createHmac('sha256', Buffer.from(token, 'base64url')).update(canonical).digest('base64url');
  const res = await fetch(`${BASE}/api/v1/ws-ticket`, { method: 'POST', headers: { 'x-victim-id': victimId, 'x-timestamp': ts, 'x-nonce': nonce, 'x-signature': sig } });
  const body = await res.json() as { ticket: string; handshake_nonce: string }; if (!body.ticket) throw new Error(JSON.stringify(body));
  const ws = new WebSocket(`ws://127.0.0.1:4000/api/v1/ws?ticket=${encodeURIComponent(body.ticket)}&device_id=${victimId}`);
  const secret = Buffer.from(body.ticket, 'base64url');
  const salt = Buffer.from(body.handshake_nonce, 'base64url');
  const a2c = Buffer.from(hkdfSync('sha256', secret, salt, Buffer.from('spc-ws-v1/a2c'), 32));
  let seq = 0;
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
  ws.on('open', () => console.log('open'));
  ws.on('message', (data) => {
    const msg = JSON.parse(String(data));
    if (msg.type === 'hello_ok') {
      seq = Number(msg.expect_seq_in ?? 0);
      ws.send(frame('ack', { reply_to: msg.id }));
      ws.send(frame('hello', { hostname: 'SEED-DESKTOP', user: 'seed', os: 'windows', cpu: 'x', ram: '8 GB', gpu: 'x', resolution: '1920x1080', java: '17', agent: '0.3.7', public_ip: '203.0.113.7', path: 'C:/spc', first: false, expect_seq_in: msg.seq }));
    }
  });
  ws.on('close', (code) => { console.log('close', code); process.exit(0); });
  setInterval(() => { if (ws.readyState === 1) ws.send(frame('ping')); }, 20_000);
  console.log('agent up');
}
main().catch((error) => { console.error(error); process.exit(1); });
