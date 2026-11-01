import type { TrainState } from '../shared/types';

/**
 * Client-side interpolation. The server sends a position every ~5 s; the browser draws at 60fps,
 * so each train glides from where it was drawn to where the server says it is, over the push
 * interval. The result is continuous motion with no extra server traffic (ADR 2).
 *
 * Positions are eased (smoothstep) rather than linear so a correction does not start and stop
 * with a visible jerk.
 */
export interface Animated {
  /** Where the train was drawn when the newest position arrived. */
  from: [number, number];
  fromBearing: number;
  /** Where the server says it is. */
  to: [number, number];
  toBearing: number;
  /** Timestamp (ms) when `to` arrived. */
  startedAt: number;
}

export const DEFAULT_DURATION_MS = 5000;
/** A jump larger than this is a correction, not motion: snap instead of sliding across the city. */
export const SNAP_DEGREES = 0.05;

export function smoothstep(t: number): number {
  const x = Math.min(1, Math.max(0, t));
  return x * x * (3 - 2 * x);
}

/** Shortest signed difference between two bearings, in (-180, 180]. */
export function bearingDelta(from: number, to: number): number {
  let d = ((to - from + 540) % 360) - 180;
  if (d === -180) d = 180;
  return d;
}

export function lerpBearing(from: number, to: number, t: number): number {
  return (from + bearingDelta(from, to) * t + 360) % 360;
}

/**
 * Updates the animation state for the trains that are currently on the map.
 *
 * @param previous animation state from the last frame (mutated in place for speed)
 * @param trains   newest server state
 * @param nowMs    current time
 */
export function reconcile(
  previous: Map<string, Animated>,
  trains: Map<string, TrainState>,
  nowMs: number,
  durationMs = DEFAULT_DURATION_MS,
): Map<string, Animated> {
  for (const [id, t] of trains) {
    const target: [number, number] = [t.lon, t.lat];
    const existing = previous.get(id);
    if (!existing) {
      previous.set(id, { from: target, fromBearing: t.bearing, to: target, toBearing: t.bearing, startedAt: nowMs });
      continue;
    }
    if (existing.to[0] === target[0] && existing.to[1] === target[1]) continue;
    const current = sample(existing, nowMs, durationMs);
    const jump = Math.abs(current.position[0] - target[0]) + Math.abs(current.position[1] - target[1]);
    const from = jump > SNAP_DEGREES ? target : current.position;
    previous.set(id, {
      from,
      fromBearing: jump > SNAP_DEGREES ? t.bearing : current.bearing,
      to: target,
      toBearing: t.bearing,
      startedAt: nowMs,
    });
  }
  for (const id of previous.keys()) {
    if (!trains.has(id)) previous.delete(id);
  }
  return previous;
}

/** Position and bearing of one train at `nowMs`. */
export function sample(a: Animated, nowMs: number, durationMs = DEFAULT_DURATION_MS): {
  position: [number, number];
  bearing: number;
} {
  const t = smoothstep((nowMs - a.startedAt) / durationMs);
  return {
    position: [a.from[0] + (a.to[0] - a.from[0]) * t, a.from[1] + (a.to[1] - a.from[1]) * t],
    bearing: lerpBearing(a.fromBearing, a.toBearing, t),
  };
}
