import type { Map as MlMap, GeoJSONSource } from 'maplibre-gl';
import type { FeatureCollection } from 'geojson';

/** Adds (or refreshes) the line and station layers drawn by MapLibre under the trains. */
export function setStaticLayers(map: MlMap, lines: FeatureCollection, stations: FeatureCollection) {
  const lineSrc = map.getSource('lines') as GeoJSONSource | undefined;
  if (lineSrc) {
    lineSrc.setData(lines);
  } else {
    map.addSource('lines', { type: 'geojson', data: lines });
    map.addLayer({
      id: 'lines-casing',
      type: 'line',
      source: 'lines',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#000000', 'line-width': ['interpolate', ['linear'], ['zoom'], 9, 3, 15, 9], 'line-opacity': 0.6 },
    });
    map.addLayer({
      id: 'lines',
      type: 'line',
      source: 'lines',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': ['get', 'color'],
        'line-width': ['interpolate', ['linear'], ['zoom'], 9, 1.5, 15, 5],
        'line-opacity': 0.85,
      },
    });
  }
  const stationSrc = map.getSource('stations') as GeoJSONSource | undefined;
  if (stationSrc) {
    stationSrc.setData(stations);
  } else {
    map.addSource('stations', { type: 'geojson', data: stations });
    map.addLayer({
      id: 'stations',
      type: 'circle',
      source: 'stations',
      paint: {
        'circle-radius': ['interpolate', ['linear'], ['zoom'], 9, 1.5, 13, 3.5, 16, 6],
        'circle-color': '#ffffff',
        'circle-stroke-color': '#11161d',
        'circle-stroke-width': 1,
      },
    });
  }
}

/** Shows only the given systems/routes on the static layers. */
export function filterStaticLayers(map: MlMap, systems: Set<string>, hiddenRoutes: Set<string>) {
  const sys = ['in', ['get', 'system'], ['literal', [...systems]]];
  const route = ['!', ['in', ['concat', ['get', 'system'], ':', ['get', 'routeId']], ['literal', [...hiddenRoutes]]]];
  if (map.getLayer('lines')) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const f = ['all', sys, route] as any;
    map.setFilter('lines', f);
    map.setFilter('lines-casing', f);
  }
  if (map.getLayer('stations')) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    map.setFilter('stations', sys as any);
  }
}
