import type { StyleSpecification } from 'maplibre-gl';

/**
 * Free vector base tiles from OpenFreeMap (no key). Override with VITE_MAP_STYLE_URL, e.g. a
 * self-hosted Protomaps style. Verify the style name on https://openfreemap.org before relying on it.
 */
export const DEFAULT_STYLE_URL: string =
  (import.meta.env.VITE_MAP_STYLE_URL as string | undefined) ?? 'https://tiles.openfreemap.org/styles/dark';

/** Plain dark background used when the tile server is unreachable (offline, CI, tests). */
export const FALLBACK_STYLE: StyleSpecification = {
  version: 8,
  name: 'tracknyc-fallback',
  sources: {},
  layers: [{ id: 'background', type: 'background', paint: { 'background-color': '#0b0f14' } }],
};

/** Fetches the style JSON with a timeout so a slow tile server never blocks the train layer. */
export async function loadStyle(url: string = DEFAULT_STYLE_URL, timeoutMs = 4000): Promise<{
  style: StyleSpecification | string;
  fallback: boolean;
}> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { signal: ctrl.signal });
    if (!res.ok) throw new Error(`style HTTP ${res.status}`);
    const json = (await res.json()) as StyleSpecification;
    if (json.version !== 8) throw new Error('not a v8 style');
    return { style: json, fallback: false };
  } catch {
    return { style: FALLBACK_STYLE, fallback: true };
  } finally {
    clearTimeout(timer);
  }
}

/** Initial camera: the NYC region. */
export const NYC_VIEW = { center: [-73.94, 40.73] as [number, number], zoom: 11 };
