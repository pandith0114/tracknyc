import { describe, expect, it } from 'vitest';
import type { TrainState } from '../shared/types';
import { bearingDelta, DEFAULT_DURATION_MS, lerpBearing, reconcile, sample, smoothstep } from './interpolate';

function train(id: string, lon: number, lat: number, bearing = 0): TrainState {
  return {
    id, system: 'subway', feedId: 'subway-l', routeId: 'L', tripId: id, lat, lon, bearing, speedMps: 9,
    status: 'IN_TRANSIT', positionSource: 'INTERPOLATED', progress: 0.5, freshness: 'LIVE',
  } as TrainState;
}

describe('smoothstep', () => {
  it('clamps and is symmetric around the midpoint', () => {
    expect(smoothstep(-1)).toBe(0);
    expect(smoothstep(0)).toBe(0);
    expect(smoothstep(0.5)).toBe(0.5);
    expect(smoothstep(1)).toBe(1);
    expect(smoothstep(2)).toBe(1);
    expect(smoothstep(0.25) + smoothstep(0.75)).toBeCloseTo(1, 10);
  });
});

describe('bearings', () => {
  it('takes the short way around north', () => {
    expect(bearingDelta(350, 10)).toBe(20);
    expect(bearingDelta(10, 350)).toBe(-20);
    expect(bearingDelta(0, 180)).toBe(180);
    expect(lerpBearing(350, 10, 0.5)).toBeCloseTo(0, 10);
    expect(lerpBearing(10, 350, 0.5)).toBeCloseTo(0, 10);
    expect(lerpBearing(90, 270, 0.5)).toBeCloseTo(180, 10);
  });
});

describe('reconcile + sample', () => {
  it('glides from the drawn position to the new one', () => {
    const anim = reconcile(new Map(), new Map([['a', train('a', -74, 40.7)]]), 0);
    expect(sample(anim.get('a')!, 0).position).toEqual([-74, 40.7]);

    // A new position arrives 5 s later; halfway through the next interval the train is halfway.
    reconcile(anim, new Map([['a', train('a', -73.98, 40.7)]]), 5000);
    const mid = sample(anim.get('a')!, 7500);
    expect(mid.position[0]).toBeCloseTo(-73.99, 6);
    const end = sample(anim.get('a')!, 5000 + DEFAULT_DURATION_MS);
    expect(end.position).toEqual([-73.98, 40.7]);
  });

  it('eases: a quarter through the interval it has moved less than a quarter of the way', () => {
    // 0.01 deg is a normal 5 s hop; anything beyond SNAP_DEGREES would snap instead.
    const anim = reconcile(new Map(), new Map([['a', train('a', 0, 0)]]), 0);
    reconcile(anim, new Map([['a', train('a', 0.01, 0)]]), 0);
    expect(sample(anim.get('a')!, 1250).position[0]).toBeLessThan(0.0025);
    expect(sample(anim.get('a')!, 3750).position[0]).toBeGreaterThan(0.0075);
  });

  it('interrupts an in-flight animation from where it is, not from where it started', () => {
    const anim = reconcile(new Map(), new Map([['a', train('a', 0, 0)]]), 0);
    reconcile(anim, new Map([['a', train('a', 0.01, 0)]]), 0);
    const atInterrupt = sample(anim.get('a')!, 2500).position[0];
    reconcile(anim, new Map([['a', train('a', 0.02, 0)]]), 2500);
    expect(sample(anim.get('a')!, 2500).position[0]).toBeCloseTo(atInterrupt, 10);
  });

  it('snaps instead of sliding when a train is corrected across the map', () => {
    const anim = reconcile(new Map(), new Map([['a', train('a', -74, 40.7)]]), 0);
    reconcile(anim, new Map([['a', train('a', -73.8, 40.75)]]), 0);
    expect(sample(anim.get('a')!, 1).position[0]).toBeCloseTo(-73.8, 6);
  });

  it('forgets trains that disappeared', () => {
    const anim = reconcile(new Map(), new Map([['a', train('a', 0, 0)], ['b', train('b', 1, 1)]]), 0);
    expect(anim.size).toBe(2);
    reconcile(anim, new Map([['a', train('a', 0, 0)]]), 1000);
    expect([...anim.keys()]).toEqual(['a']);
  });
});
