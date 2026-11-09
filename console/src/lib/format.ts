export function bytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const index = Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024)));
  return `${(value / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}

export function ago(date: string | Date | null | undefined): string {
  if (!date) return 'never';
  const ms = Date.now() - new Date(date).getTime();
  if (ms < 5_000) return 'now';
  if (ms < 60_000) return `${Math.floor(ms / 1000)}s`;
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m`;
  if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)}h`;
  if (ms < 30 * 86_400_000) return `${Math.floor(ms / 86_400_000)}d`;
  return new Date(date).toLocaleDateString('en-GB');
}

export const dateTime = (date: string | Date | null | undefined): string =>
  date ? new Date(date).toLocaleString('en-GB', { hour12: false }) : '—';

export const short = (value: string | null | undefined, length = 8): string =>
  value ? value.replace(/-/g, '').slice(0, length) : '—';

const FLAGS: Record<string, string> = {
  ES: '🇪🇸', US: '🇺🇸', GB: '🇬🇧', DE: '🇩🇪', FR: '🇫🇷', IT: '🇮🇹', NL: '🇳🇱', PT: '🇵🇹',
  BR: '🇧🇷', AR: '🇦🇷', MX: '🇲🇽', CA: '🇨🇦', TR: '🇹🇷', RU: '🇷🇺', UA: '🇺🇦', PL: '🇵🇱',
  IN: '🇮🇳', CN: '🇨🇳', JP: '🇯🇵', KR: '🇰🇷', AU: '🇦🇺', SG: '🇸🇬', AE: '🇦🇪', IL: '🇮🇱'
};

export const flag = (code: string | null | undefined): string => (code && FLAGS[code]) || '🏴';

import { isWindows, isMac } from '@/lib/os';

export const osIcon = (os: string): string => (isWindows(os) ? '⊞' : isMac(os) ? '' : '🐧');

export function copy(text: string): void {
  void navigator.clipboard?.writeText(text);
}

export function mask(value: string, visible = 10): string {
  if (value.length <= visible) return value;
  return `${value.slice(0, visible)}…${value.slice(-4)}`;
}

export const osTone = (os: string) => (isWindows(os) ? 'text-brand' : isMac(os) ? 'text-ink' : 'text-warn');
