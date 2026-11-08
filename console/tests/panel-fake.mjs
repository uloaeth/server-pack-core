// Fake panel for the PanelSocket acceptance run. Node + ws, no framework.
// Usage: node tests/panel-fake.mjs <port> <phase> <exec-file>
import http from 'node:http';
import { createHash, createHmac, hkdfSync, randomBytes, randomUUID } from 'node:crypto';
import { WebSocketServer } from 'ws';

const port = Number(process.argv[2]);
const phase = process.argv[3] ?? 'one';
const execFile = process.argv[4];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const tickets = new Map();
const openTasks = new Set();
let connections = 0;
let lastIn = 0;
let cmdSent = false;
const CMD_ID = '11111111-1111-4111-8111-111111111111';
let blocked = new Set();

const tasks = phase === 'two' ? [] : [];

function b64(bytes) {
  return Buffer.from(bytes).toString('base64url');
}

function sha256hex(data) {
  return createHash('sha256').update(data).digest('hex');
}

function sign(key, seq, nonce, payload) {
  const input = Buffer.concat([
    Buffer.from(String(seq)),
    Buffer.from([0x1f]),
    Buffer.from(nonce),
    Buffer.from([0x1f]),
    Buffer.from(payload)
  ]);
  return createHmac('sha256', key).update(input).digest('base64url');
}

function frame(key, type, seq, fields, id = randomUUID()) {
  const nonce = randomBytes(16).toString('base64url');
  let payload = `{"v":1,"id":"${id}","seq":${seq},"ts":${Math.floor(Date.now() / 1000)},"type":"${type}","nonce":"${nonce}"`;
  for (const [name, value] of fields) {
    payload += `,"${name}":${typeof value === 'string' ? JSON.stringify(value) : JSON.stringify(value)}`;
  }
  payload += '}';
  const body = payload.slice(0, -1);
  const sig = sign(key, seq, nonce, payload);
  return { id, text: `${body},"sig":"${sig}"}` };
}

const server = http.createServer((req, res) => {
  console.error('REQ', req.method, req.url);
  const url = new URL(req.url, `http://127.0.0.1:${port}`);
  if (req.method === 'POST' && url.pathname === '/api/v1/ws-ticket') {
    req.resume();
    req.on('end', () => {
      const device = String(req.headers['x-victim-id'] ?? '');
      if (!UUID.test(device)) {
        res.writeHead(401, { 'content-type': 'application/json' });
        res.end('{"error":"bad_signature"}');
        return;
      }
      const ticket = randomBytes(32).toString('base64url');
      const nonce = randomBytes(16).toString('base64url');
      tickets.set(ticket, { device, nonce });
      res.writeHead(201, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ticket, expires_in: 30, handshake_nonce: nonce, device_id: device }));
    });
    return;
  }
  if (req.method === 'GET' && url.pathname === '/api/v1/tasks') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ tasks: [...openTasks].map((id) => ({ id, name: 'shell', params: {} })) }));
    return;
  }
  res.writeHead(404).end();
});

const wss = new WebSocketServer({ server });

wss.on('connection', (socket, req) => {
  const url = new URL(req.url, 'http://x');
  const record = tickets.get(url.searchParams.get('ticket'));
  if (!record || record.device !== url.searchParams.get('device_id')) {
    socket.close(4401, 'auth');
    return;
  }
  tickets.delete(url.searchParams.get('ticket'));
  connections++;
  const secret = Buffer.from(url.searchParams.get('ticket'), 'base64url');
  const salt = Buffer.from(record.nonce, 'base64url');
  const c2a = Buffer.from(hkdfSync('sha256', secret, salt, Buffer.from('spc-ws-v1/c2a'), 32));
  const a2c = Buffer.from(hkdfSync('sha256', secret, salt, Buffer.from('spc-ws-v1/a2c'), 32));
  const state = { lastIn: Number(url.searchParams.get('expect_seq_in') ?? 0), lastOut: 0, outSeq: 1 };
  state.lastIn = Math.max(state.lastIn, lastIn);
  state.outSeq = state.lastIn + 1;

  const helloOk = frame(c2a, 'hello_ok', state.outSeq++, [
    ['server_time', Math.floor(Date.now() / 1000)],
    ['heartbeat_sec', 30],
    ['poll_sec', 20],
    ['expect_seq_in', state.lastIn],
    ['expect_seq_out', state.lastOut]
  ]);
  state.lastOut = state.outSeq - 1;
  socket.send(helloOk.text);

  let ready = false;
  const pendingCmds = [];
  let duplicateSent = false;

  socket.on('message', (data) => {
    const text = String(data);
    const split = text.lastIndexOf(',"sig":');
    const payload = text.slice(0, split);
    const json = JSON.parse(text);
    const computed = sign(a2c, json.seq, json.nonce, `${payload}}`);
    if (computed !== json.sig) {
      socket.close(4401, 'bad_hmac');
      return;
    }
    if (json.seq <= state.lastIn) {
      socket.close(4400, 'seq');
      return;
    }
    state.lastIn = json.seq;
    lastIn = Math.max(lastIn, json.seq);
    if (json.type === 'ack') {
      console.error('ACK', json.reply_to);
      if (json.reply_to === helloOk.id) ready = true;
      return;
    }
    if (json.type === 'hello') {
      if (!ready) {
        socket.close(4400, 'ack');
        return;
      }
      ready = true;
      const helloAck = frame(c2a, 'ack', state.outSeq++, [['reply_to', json.id]]);
      state.lastOut = state.outSeq - 1;
      socket.send(helloAck.text);
      if (phase === 'one' && !cmdSent) {
        cmdSent = true;
        const cmd = frame(c2a, 'cmd', state.outSeq++, [
          ['name', 'shell'],
          ['args', { command: `echo one >> ${execFile}` }],
          ['task_id', '3f0c4b5e-7a21-4c8d-9e11-2b6a8d0c4f77'],
          ['timeout_ms', 30000]
        ], CMD_ID);
        state.lastOut = state.outSeq - 1;
        openTasks.add('3f0c4b5e-7a21-4c8d-9e11-2b6a8d0c4f77');
        setTimeout(() => openTasks.delete('3f0c4b5e-7a21-4c8d-9e11-2b6a8d0c4f77'), 700);
        socket.send(cmd.text);
        setTimeout(() => {
          // Exact retransmit, same id, seq, nonce and signature.
          if (!duplicateSent && socket.readyState === socket.OPEN) {
            duplicateSent = true;
            socket.send(cmd.text);
          }
        }, 300);
        setTimeout(() => socket.terminate(), 1200);
      } else if (phase === 'two') {
        // Run two: the same frame id, as a post-restart duplicate.
        setTimeout(() => {
          const again = frame(c2a, 'cmd', state.outSeq++, [
            ['name', 'shell'],
            ['args', { command: `echo one >> ${execFile}` }],
            ['task_id', '3f0c4b5e-7a21-4c8d-9e11-2b6a8d0c4f77'],
            ['timeout_ms', 30000]
          ], CMD_ID);
          state.lastOut = state.outSeq - 1;
          socket.send(again.text);
        }, 500);
        setTimeout(() => {
          console.log(JSON.stringify({ connections, phase }));
          process.exit(0);
        }, 3000);
      }
    }
  });
});

const done = setInterval(() => {
  if (phase === 'one' && connections >= 2) {
    clearInterval(done);
    console.log(JSON.stringify({ connections, phase }));
    setTimeout(() => process.exit(0), 800);
  }
}, 200);

wss.on('error', (error) => {
  console.error(error);
  process.exit(1);
});

server.listen(port, '127.0.0.1');
setTimeout(() => {
  console.log(JSON.stringify({ connections, phase }));
  process.exit(0);
}, 25_000);
