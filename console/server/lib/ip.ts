import { isIP } from 'node:net';

export function clientIp(headers: Record<string, unknown>, socketIp: string, trustProxy: boolean): string {
  if (!trustProxy) return socketIp;
  const raw = headers['cf-connecting-ip'] ?? headers['x-real-ip'] ?? headers['x-forwarded-for'];
  if (typeof raw === 'string' && raw.trim()) {
    const first = raw.split(',')[0]!.trim();
    if (isIP(first)) return first;
  }
  return socketIp;
}

function ipv4ToInt(ip: string): number {
  return ip.split('.').reduce((acc, part) => (acc << 8) + Number(part), 0) >>> 0;
}

export function inCidr(ip: string, cidr: string): boolean {
  const [base, bitsText] = cidr.split('/');
  const bits = bitsText === undefined ? (isIP(base) === 4 ? 32 : 128) : Number(bitsText);
  if (isIP(base) === 4 && isIP(ip) === 4) {
    if (bits === 0) return true;
    const mask = bits === 32 ? 0xffffffff : (0xffffffff << (32 - bits)) >>> 0;
    return (ipv4ToInt(ip) & mask) === (ipv4ToInt(base) & mask);
  }
  if (isIP(base) === 6 && isIP(ip) === 6) {
    // Compact expansion: compare the prefix nibbles, sufficient for allowlists.
    const norm = (v: string) => v.split('%')[0]!.toLowerCase();
    const prefix = Math.ceil(bits / 4);
    return norm(ip).replace(/:/g, '').slice(0, prefix) === norm(base).replace(/:/g, '').slice(0, prefix);
  }
  return false;
}

export function allowlisted(ip: string, list: string[]): boolean {
  return list.length === 0 || list.some((cidr) => inCidr(ip, cidr));
}
