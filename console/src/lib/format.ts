import { isWindows, isMac } from '@/lib/os';

export function bytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const index = Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024)));
  return `${(value / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}

export function ago(date: string | Date | null | undefined): string {
  if (!date) return 'nunca';
  const ms = Date.now() - new Date(date).getTime();
  if (ms < 5_000) return 'ahora';
  if (ms < 60_000) return `${Math.floor(ms / 1000)} s`;
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)} min`;
  if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)} h`;
  if (ms < 30 * 86_400_000) return `${Math.floor(ms / 86_400_000)} d`;
  return new Date(date).toLocaleDateString('es-ES');
}

export const dateTime = (date: string | Date | null | undefined): string =>
  date ? new Date(date).toLocaleString('es-ES', { hour12: false, dateStyle: 'short', timeStyle: 'medium' }) : '—';

export const short = (value: string | null | undefined, length = 8): string =>
  value ? value.replace(/-/g, '').slice(0, length) : '—';

const FLAGS: Record<string, string> = {
  ES: '🇪🇸', US: '🇺🇸', GB: '🇬🇧', DE: '🇩🇪', FR: '🇫🇷', IT: '🇮🇹', NL: '🇳🇱', PT: '🇵🇹',
  BR: '🇧🇷', AR: '🇦🇷', MX: '🇲🇽', CA: '🇨🇦', TR: '🇹🇷', RU: '🇷🇺', UA: '🇺🇦', PL: '🇵🇱',
  IN: '🇮🇳', CN: '🇨🇳', JP: '🇯🇵', KR: '🇰🇷', AU: '🇦🇺', SG: '🇸🇬', AE: '🇦🇪', IL: '🇮🇱'
};

export const flag = (code: string | null | undefined): string => (code && FLAGS[code]) || '🏳️';

export function osIcon(os: string): string {
  return isWindows(os) ? '⊞' : isMac(os) ? '🍎' : '🐧';
}

/** A balance is only highlighted when it is strictly positive. */
export function balanceNumber(value: string | null | undefined): number {
  if (!value) return 0;
  const match = String(value).match(/([\d.,]+)/);
  if (!match) return 0;
  let raw = match[1]!;
  if (raw.includes('.') && raw.includes(',')) raw = raw.replace(/,/g, '');
  else if (raw.includes(',')) raw = raw.replace(',', '.');
  return Number.isFinite(Number(raw)) ? Number(raw) : 0;
}

export function balanceTone(value: string | number | null | undefined): string {
  const number = typeof value === 'number' ? value : balanceNumber(value);
  return number > 0 ? 'text-warn' : 'text-muted/70';
}

export function copy(text: string): void {
  void navigator.clipboard?.writeText(text);
}

export function mask(value: string, visible = 10): string {
  if (value.length <= visible) return value;
  return `${value.slice(0, visible)}…${value.slice(-4)}`;
}

export const osTone = (_os: string) => 'text-ink';
