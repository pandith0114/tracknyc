import { IconLayer, ScatterplotLayer, TextLayer } from '@deck.gl/layers';
import type { PickingInfo } from '@deck.gl/core';
import type { RouteView, TrainState } from '../shared/types';
import { delayClass, hexToRgb } from '../shared/format';

export type ColorMode = 'line' | 'delay';

/** A train ready to draw: its (possibly animated) position plus the state it came from. */
export interface DrawnTrain {
  state: TrainState;
  position: [number, number];
  bearing: number;
  opacity: number; // 0..1, used for stale fade-out
}

const DELAY_RGB: Record<string, [number, number, number]> = {
  'on-time': [46, 204, 113],
  late: [245, 166, 35],
  'very-late': [231, 76, 60],
  unknown: [149, 165, 166],
};

const ARROW_SVG =
  'data:image/svg+xml;charset=utf-8,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64"><path d="M32 2 L46 26 L18 26 Z" fill="#fff"/></svg>',
  );

export function routeKey(system: string, routeId?: string) {
  return `${system}:${routeId ?? ''}`;
}

/** Freshness → opacity: stale trains fade, vanished trains fade further before removal. */
export function freshnessOpacity(t: TrainState): number {
  if (t.freshness === 'MISSING') return 0.3;
  if (t.freshness === 'STALE') return 0.45;
  return 1;
}

export function trainLayers(
  trains: DrawnTrain[],
  routes: Map<string, RouteView>,
  colorMode: ColorMode,
  selectedId: string | null,
  onClick: (t: TrainState | null) => void,
) {
  const fill = (d: DrawnTrain): [number, number, number, number] => {
    const a = Math.round(255 * d.opacity);
    if (colorMode === 'delay') return [...DELAY_RGB[delayClass(d.state.delaySec)], a];
    const r = routes.get(routeKey(d.state.system, d.state.routeId));
    return [...hexToRgb(r?.color ?? '#808183'), a];
  };
  const outline = (d: DrawnTrain): [number, number, number, number] => {
    const a = Math.round(255 * d.opacity);
    if (d.state.id === selectedId) return [255, 255, 255, a];
    if (colorMode === 'line') {
      const cls = delayClass(d.state.delaySec);
      if (cls === 'late' || cls === 'very-late') return [...DELAY_RGB[cls], a];
    }
    return [15, 20, 26, a];
  };
  const pick = (info: PickingInfo<DrawnTrain>) => {
    onClick(info.object ? info.object.state : null);
    return true;
  };
  return [
    new IconLayer<DrawnTrain>({
      id: 'train-arrows',
      data: trains,
      getPosition: (d) => d.position,
      getIcon: () => ({ url: ARROW_SVG, width: 64, height: 64, anchorY: 32, mask: true }),
      getSize: 34,
      sizeUnits: 'pixels',
      getAngle: (d) => -d.bearing,
      getColor: fill,
      updateTriggers: { getColor: [colorMode] },
    }),
    new ScatterplotLayer<DrawnTrain>({
      id: 'trains',
      data: trains,
      pickable: true,
      getPosition: (d) => d.position,
      getRadius: (d) => (d.state.id === selectedId ? 10 : 8),
      radiusUnits: 'pixels',
      stroked: true,
      lineWidthUnits: 'pixels',
      getLineWidth: (d) => (d.state.id === selectedId ? 3 : 2),
      getFillColor: fill,
      getLineColor: outline,
      onClick: pick,
      updateTriggers: { getFillColor: [colorMode], getLineColor: [colorMode, selectedId], getRadius: [selectedId] },
    }),
    new TextLayer<DrawnTrain>({
      id: 'train-labels',
      data: trains,
      getPosition: (d) => d.position,
      getText: (d) => (routes.get(routeKey(d.state.system, d.state.routeId))?.shortName ?? d.state.routeId ?? '').slice(0, 2),
      getSize: 11,
      sizeUnits: 'pixels',
      fontWeight: 700,
      getColor: (d) => {
        const r = routes.get(routeKey(d.state.system, d.state.routeId));
        return [...hexToRgb(colorMode === 'line' ? r?.textColor ?? '#ffffff' : '#000000'), Math.round(255 * d.opacity)];
      },
      getTextAnchor: 'middle',
      getAlignmentBaseline: 'center',
      characterSet: 'auto',
      updateTriggers: { getColor: [colorMode] },
    }),
  ];
}
