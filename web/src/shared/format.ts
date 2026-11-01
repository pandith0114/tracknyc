/** Small formatting helpers shared by panels and badges. */

export function ageLabel(seconds: number | undefined | null): string {
  if (seconds == null || Number.isNaN(seconds)) return 'no data yet';
  if (seconds < 60) return `${Math.round(seconds)} s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  return `${Math.floor(seconds / 3600)} h ago`;
}

export function minutesUntil(iso: string | undefined, now: number = Date.now()): string {
  if (!iso) return '—';
  const min = Math.round((Date.parse(iso) - now) / 60000);
  if (min <= 0) return 'now';
  return `${min} min`;
}

export function delayLabel(delaySec: number | undefined | null): string {
  if (delaySec == null) return 'no schedule match';
  if (delaySec < 60) return 'on time';
  return `${Math.round(delaySec / 60)} min late`;
}

export type DelayClass = 'on-time' | 'late' | 'very-late' | 'unknown';

/** Amber from 2 minutes late, red from 5 minutes. */
export function delayClass(delaySec: number | undefined | null): DelayClass {
  if (delaySec == null) return 'unknown';
  if (delaySec >= 300) return 'very-late';
  if (delaySec >= 120) return 'late';
  return 'on-time';
}

export function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
