import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MapGeoJSONFeature } from 'maplibre-gl';
import { MapShell, type MapHandles } from '../shared/MapShell';
import { filterStaticLayers, setStaticLayers } from '../shared/staticLayers';
import { FeedHealthBadge } from '../shared/FeedHealthBadge';
import { StaleBanner } from '../shared/StaleBanner';
import { Filters } from '../shared/Filters';
import { api } from '../shared/api';
import { useNow, usePolling } from '../shared/usePolling';
import { useTrainStream } from '../shared/useTrainStream';
import type { FeedsHealthResponse, RouteView, SystemView, TrainState } from '../shared/types';
import { freshnessOpacity, routeKey, trainLayers, type ColorMode, type DrawnTrain } from './layers';
import { reconcile, sample, type Animated } from './interpolate';
import { TrainPanel } from './TrainPanel';
import { StationPanel } from './StationPanel';

const HEALTH_POLL_MS = 20_000;

type Selection = { kind: 'train'; id: string } | { kind: 'station'; id: string } | null;

/**
 * The live map. Positions arrive over SSE every ~5 s (snapshot then diffs) and are animated
 * client-side at 60fps, so trains glide instead of jumping (ADR 2).
 */
export function TrainsPage() {
  const [handles, setHandles] = useState<MapHandles | null>(null);
  const [systems, setSystems] = useState<SystemView[]>([]);
  const [selection, setSelection] = useState<Selection>(null);
  const [colorMode, setColorMode] = useState<ColorMode>('line');
  const [hiddenSystems, setHiddenSystems] = useState<Set<string>>(new Set());
  const [hiddenRoutes, setHiddenRoutes] = useState<Set<string>>(new Set());
  const now = useNow(1000);

  const stream = useTrainStream();
  // Feed health also arrives on the stream; polling is the fallback while it is connecting.
  const polledHealth = usePolling(api.feedHealth, HEALTH_POLL_MS);
  const health: FeedsHealthResponse | null = useMemo(
    () => (stream.health ? { ...(polledHealth.data ?? { generatedAt: new Date().toISOString(), mode: 'replay', staleAfterSeconds: 60 }), feeds: stream.health } as FeedsHealthResponse : polledHealth.data),
    [stream.health, polledHealth.data],
  );

  const animations = useRef<Map<string, Animated>>(new Map());
  const trainsRef = useRef(stream.trains);
  trainsRef.current = stream.trains;
  const stateRef = useRef({ colorMode, selection, hiddenSystems, hiddenRoutes, handles });
  stateRef.current = { colorMode, selection, hiddenSystems, hiddenRoutes, handles };

  const onReady = useCallback((h: MapHandles) => setHandles(h), []);

  useEffect(() => {
    const ctrl = new AbortController();
    api.systems(ctrl.signal).then(setSystems).catch(() => undefined);
    return () => ctrl.abort();
  }, []);

  // Deep links and tests select a station without having to hit the right pixel on the canvas.
  useEffect(() => {
    const onSelect = (e: Event) => setSelection({ kind: 'station', id: (e as CustomEvent<string>).detail });
    window.addEventListener('tracknyc:select-station', onSelect);
    return () => window.removeEventListener('tracknyc:select-station', onSelect);
  }, []);

  // Static layers: lines and stations, plus click handling for the departure board.
  useEffect(() => {
    if (!handles) return;
    const ctrl = new AbortController();
    Promise.all([api.lines(ctrl.signal), api.stations(ctrl.signal)])
      .then(([lines, stations]) => {
        setStaticLayers(handles.map, lines, stations);
        handles.map.on('click', 'stations', (e) => {
          const f = e.features?.[0] as MapGeoJSONFeature | undefined;
          const id = f?.properties?.id as string | undefined;
          if (id) setSelection({ kind: 'station', id });
        });
        handles.map.on('mouseenter', 'stations', () => {
          handles.map.getCanvas().style.cursor = 'pointer';
        });
        handles.map.on('mouseleave', 'stations', () => {
          handles.map.getCanvas().style.cursor = '';
        });
      })
      .catch(() => undefined);
    return () => ctrl.abort();
  }, [handles]);

  const routes = useMemo(() => {
    const m = new Map<string, RouteView>();
    systems.forEach((s) => s.routes.forEach((r) => m.set(routeKey(r.system, r.id), r)));
    return m;
  }, [systems]);
  const routesRef = useRef(routes);
  routesRef.current = routes;

  useEffect(() => {
    if (!handles) return;
    const visible = new Set(systems.map((s) => s.id).filter((id) => !hiddenSystems.has(id)));
    filterStaticLayers(handles.map, visible, hiddenRoutes);
  }, [handles, systems, hiddenSystems, hiddenRoutes]);

  // One animation loop for the whole map: reconcile against the newest server state, then
  // sample every train for this frame.
  useEffect(() => {
    if (!handles) return;
    let frame = 0;
    const draw = () => {
      const ts = performance.timeOrigin + performance.now();
      const { colorMode: mode, selection: sel, hiddenSystems: hs, hiddenRoutes: hr } = stateRef.current;
      reconcile(animations.current, trainsRef.current, ts);
      const drawn: DrawnTrain[] = [];
      for (const [id, t] of trainsRef.current) {
        if (hs.has(t.system) || hr.has(routeKey(t.system, t.routeId))) continue;
        const a = animations.current.get(id);
        if (!a) continue;
        const { position, bearing } = sample(a, ts);
        drawn.push({ state: t, position, bearing, opacity: freshnessOpacity(t) });
      }
      handles.overlay.setProps({
        layers: trainLayers(drawn, routesRef.current, mode, sel?.kind === 'train' ? sel.id : null, (t: TrainState | null) =>
          setSelection(t ? { kind: 'train', id: t.id } : null)),
      });
      frame = requestAnimationFrame(draw);
    };
    frame = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(frame);
  }, [handles]);

  const toggle = (set: Set<string>, id: string) => {
    const next = new Set(set);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  };

  const locate = useCallback(() => {
    if (!navigator.geolocation || !handles) return;
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        handles.map.flyTo({ center: [pos.coords.longitude, pos.coords.latitude], zoom: 14 });
        api.nearbyStations(pos.coords.latitude, pos.coords.longitude)
          .then((near) => {
            if (near.length > 0) setSelection({ kind: 'station', id: near[0].stationId });
          })
          .catch(() => undefined);
      },
      () => undefined,
      { enableHighAccuracy: false, timeout: 10_000 },
    );
  }, [handles]);

  const visibleCount = useMemo(() => {
    let n = 0;
    for (const t of stream.trains.values()) {
      if (!hiddenSystems.has(t.system) && !hiddenRoutes.has(routeKey(t.system, t.routeId))) n++;
    }
    return n;
  }, [stream.trains, hiddenSystems, hiddenRoutes]);

  const selectedTrain = selection?.kind === 'train' ? stream.trains.get(selection.id) ?? null : null;

  return (
    <div className="app">
      <header className="topbar">
        <h1>
          Track<span>NYC</span>
        </h1>
        <span className="count" data-testid="train-count">
          {visibleCount} trains
        </span>
        <FeedHealthBadge health={health} nowMs={now} />
      </header>
      <StaleBanner health={health} connectionLost={!stream.connected && stream.seq >= 0} />
      <Filters
        systems={systems}
        hiddenSystems={hiddenSystems}
        hiddenRoutes={hiddenRoutes}
        colorMode={colorMode}
        onToggleSystem={(id) => setHiddenSystems((s) => toggle(s, id))}
        onToggleRoute={(id) => setHiddenRoutes((s) => toggle(s, id))}
        onColorMode={setColorMode}
        onLocate={locate}
      />
      <MapShell onReady={onReady} />
      {selectedTrain && (
        <TrainPanel train={selectedTrain} nowMs={now} onClose={() => setSelection(null)} />
      )}
      {selection?.kind === 'station' && (
        <StationPanel
          key={selection.id}
          stationId={selection.id}
          nowMs={now}
          onSelectTrain={(id) => setSelection({ kind: 'train', id })}
          onClose={() => setSelection(null)}
        />
      )}
      <footer className="disclaimer">
        Unofficial. Not affiliated with or endorsed by the MTA. Positions are estimates and may be wrong.
      </footer>
    </div>
  );
}
