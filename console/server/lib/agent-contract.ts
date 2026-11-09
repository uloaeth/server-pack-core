/** Single normalizer and capability authority for v1/v2. Never fork parsing. */
export const SERVER_CONTRACT = 2;

/**
 * Capabilities the server can actually serve today. F2 features land here as
 * they ship; until then the intersection is empty and the panel paints no v2
 * action. A declared-but-unsupported capability is dropped, never trusted.
 */
export const SUPPORTED_CAPABILITIES = new Set<string>([]);

export const DECLARABLE_CAPABILITIES = [
  'pty', 'input', 'media', 'tunnel', 'cloud', 'pm', 'steam', 'wc', 'triggers', 'miner'
] as const;

/** Task/action prefix -> capability. V1 names have no entry and always pass. */
const TASK_CAPABILITY: Record<string, string> = {
  pty: 'pty',
  input: 'input',
  media: 'media',
  tunnel: 'tunnel',
  cloud: 'cloud',
  pm: 'pm',
  steam: 'steam',
  wc: 'wc',
  trigger: 'triggers',
  miner: 'miner'
};

export function normalizeContract(value: unknown): number {
  // `X-Contract` is reinforcement, not the source of truth for the version.
  if (typeof value !== 'string') return 1;
  const parsed = Number(value.split(',')[0]?.trim());
  return Number.isInteger(parsed) && parsed >= 2 ? 2 : 1;
}

export function sanitizeDeclared(values: unknown): string[] {
  if (!Array.isArray(values)) return [];
  const allowed = new Set<string>(DECLARABLE_CAPABILITIES);
  const seen = new Set<string>();
  for (const value of values) {
    if (typeof value === 'string' && allowed.has(value)) seen.add(value);
    if (seen.size >= 32) break;
  }
  return [...seen].sort();
}

/** Declared ∩ supported. Sorted so persistence is deterministic. */
export function intersectCapabilities(declared: unknown): string[] {
  return sanitizeDeclared(declared).filter((capability) => SUPPORTED_CAPABILITIES.has(capability));
}

export function requiresCapability(taskName: string, params: Record<string, unknown> = {}): string | null {
  const prefix = taskName.split(/[._]/)[0] ?? taskName;
  if (TASK_CAPABILITY[taskName]) return TASK_CAPABILITY[taskName];
  if (TASK_CAPABILITY[prefix]) return TASK_CAPABILITY[prefix];
  // `agent` multiplexes subcommands; only explicit v2 ones are gated.
  const command = typeof params.command === 'string' ? params.command.trim().split(/\s+/)[0] : '';
  if (command && TASK_CAPABILITY[command]) return TASK_CAPABILITY[command];
  return null;
}

export function capabilityMissing(capabilities: string[] | null | undefined, taskName: string, params: Record<string, unknown> = {}): string | null {
  const required = requiresCapability(taskName, params);
  if (!required) return null;
  return (capabilities ?? []).includes(required) ? null : required;
}
