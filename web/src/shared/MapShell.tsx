import { useEffect, useRef, useState } from 'react';
import maplibregl, { type Map as MlMap } from 'maplibre-gl';
import { MapboxOverlay } from '@deck.gl/mapbox';
import 'maplibre-gl/dist/maplibre-gl.css';
import { loadStyle, NYC_VIEW } from './mapStyle';

export interface MapHandles {
  map: MlMap;
  overlay: MapboxOverlay;
  fallbackStyle: boolean;
}

interface Props {
  onReady: (h: MapHandles) => void;
}

/**
 * MapLibre base map with a deck.gl overlay on top. The overlay draws the animated trains;
 * static line geometry and stations are plain MapLibre layers (cheap, styleable).
 */
export function MapShell({ onReady }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const readyRef = useRef(onReady);
  readyRef.current = onReady;

  useEffect(() => {
    let map: MlMap | null = null;
    let cancelled = false;
    loadStyle().then(({ style, fallback }) => {
      if (cancelled || !container.current) return;
      const problem = webglProblem();
      if (problem) {
        setFailure(problem);
        return;
      }
      try {
        map = new maplibregl.Map({
          container: container.current,
          style,
          center: NYC_VIEW.center,
          zoom: NYC_VIEW.zoom,
          attributionControl: { compact: true },
          // Lets Playwright read pixels back; negligible cost here.
          preserveDrawingBuffer: true,
        });
        map.addControl(new maplibregl.NavigationControl({ visualizePitch: false }), 'bottom-right');
        const overlay = new MapboxOverlay({ interleaved: false, layers: [] });
        map.addControl(overlay);
        // `load` waits for basemap tiles; if a blocker (e.g. Brave Shields) stalls them, the
        // trains and stations must still appear, so fall back to "style is ready".
        let announced = false;
        const announce = () => {
          if (announced || cancelled || !map) return;
          announced = true;
          readyRef.current({ map, overlay, fallbackStyle: fallback });
        };
        map.on('load', announce);
        setTimeout(() => {
          if (map?.isStyleLoaded()) announce();
        }, 5000);
      } catch (e) {
        setFailure(`The map could not start (${e instanceof Error ? e.message : String(e)}).`);
      }
    });
    return () => {
      cancelled = true;
      map?.remove();
    };
  }, []);

  return (
    <>
      <div ref={container} className="map" data-testid="map" />
      {failure && (
        <div className="map-error" role="alert" data-testid="map-error">
          <h2>The map needs WebGL</h2>
          <p>{failure}</p>
          <p>If you are using <b>Brave</b>:</p>
          <ol>
            <li>
              Open <code>brave://settings/system</code>, turn on <b>Use graphics acceleration when available</b>,
              then relaunch Brave.
            </li>
            <li>
              Click the lion icon in the address bar and set <b>Block fingerprinting</b> to <b>Standard</b> (or
              turn Shields off for this site).
            </li>
            <li>
              Check <code>brave://gpu</code>: <b>WebGL2</b> should say "Hardware accelerated".
            </li>
          </ol>
          <p className="muted small">Any other up-to-date browser with WebGL2 also works.</p>
        </div>
      )}
    </>
  );
}

/** Returns a human-readable reason when WebGL2 is unavailable, else null. */
function webglProblem(): string | null {
  try {
    const gl = document.createElement('canvas').getContext('webgl2');
    return gl ? null : 'This browser has WebGL2 disabled or blocked.';
  } catch {
    return 'This browser has WebGL2 disabled or blocked.';
  }
}
