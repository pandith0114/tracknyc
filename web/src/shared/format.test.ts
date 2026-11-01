import { describe, expect, it } from 'vitest';
import { ageLabel, delayClass, delayLabel, hexToRgb, minutesUntil } from './format';

describe('format helpers', () => {
  it('describes feed age', () => {
    expect(ageLabel(undefined)).toBe('no data yet');
    expect(ageLabel(8)).toBe('8 s ago');
    expect(ageLabel(125)).toBe('2 min ago');
    expect(ageLabel(7300)).toBe('2 h ago');
  });

  it('counts minutes to an arrival', () => {
    const now = Date.parse('2026-10-28T12:00:00Z');
    expect(minutesUntil(undefined, now)).toBe('—');
    expect(minutesUntil('2026-10-28T12:00:10Z', now)).toBe('now');
    expect(minutesUntil('2026-10-28T12:03:00Z', now)).toBe('3 min');
  });

  it('colours delays: amber from 2 min, red from 5', () => {
    expect(delayClass(null)).toBe('unknown');
    expect(delayClass(30)).toBe('on-time');
    expect(delayClass(120)).toBe('late');
    expect(delayClass(600)).toBe('very-late');
    expect(delayLabel(30)).toBe('on time');
    expect(delayLabel(240)).toBe('4 min late');
    expect(delayLabel(undefined)).toBe('no schedule match');
  });

  it('parses hex colours', () => {
    expect(hexToRgb('#A7A9AC')).toEqual([167, 169, 172]);
    expect(hexToRgb('fff')).toEqual([255, 255, 255]);
  });
});
