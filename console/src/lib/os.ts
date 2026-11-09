/** The agent reports `os.name + " " + os.version`; never compare it verbatim. */
export function isWindows(os?: string | null): boolean {
  return /^win/i.test((os ?? '').trim());
}

export function isMac(os?: string | null): boolean {
  return /^(mac|darwin)/i.test((os ?? '').trim());
}

export function isLinux(os?: string | null): boolean {
  return /^linux/i.test((os ?? '').trim());
}
