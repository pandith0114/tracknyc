import type { Page, Route } from '@playwright/test';

// Small hand-made API responses (synthetic; coordinates approximate) so the smoke test runs
// without the backend. Shapes mirror the real JSON produced by the Spring service.
const now = () => new Date().toISOString();
const inMin = (m: number) => new Date(Date.now() + m * 60_000).toISOString();

export const systems = [
  {
    id: 'subway',
    displayName: 'Subway',
    attribution: 'Data: MTA (not an official MTA product)',
    replay: true,
    bounds: [-74.01, 40.64, -73.83, 40.8],
    routes: [
      { system: 'subway', id: 'L', publicId: 'subway:L', shortName: 'L', longName: '14 St-Canarsie Local', color: '#A7A9AC', textColor: '#000000' },
      { system: 'subway', id: '7', publicId: 'subway:7', shortName: '7', longName: 'Flushing Local', color: '#B933AD', textColor: '#FFFFFF' },
    ],
  },
];

export const lines = {
  type: 'FeatureCollection',
  features: [
    { type: 'Feature', properties: { system: 'subway', routeId: 'L', route: 'L', color: '#A7A9AC', shapeId: 'L..N' },
      geometry: { type: 'LineString', coordinates: [[-74.0025, 40.7398], [-73.9815, 40.7307], [-73.9567, 40.7173], [-73.9119, 40.6995]] } },
    { type: 'Feature', properties: { system: 'subway', routeId: '7', route: '7', color: '#B933AD', shapeId: '7..N' },
      geometry: { type: 'LineString', coordinates: [[-74.002, 40.7555], [-73.9761, 40.7513], [-73.9402, 40.7507], [-73.8301, 40.7596]] } },
  ],
};

export const stations = {
  type: 'FeatureCollection',
  features: [
    { type: 'Feature', properties: { id: 'subway:L01', system: 'subway', name: '8 Av', routes: ['L'] }, geometry: { type: 'Point', coordinates: [-74.0025, 40.7398] } },
    { type: 'Feature', properties: { id: 'subway:L08', system: 'subway', name: 'Bedford Av', routes: ['L'] }, geometry: { type: 'Point', coordinates: [-73.9567, 40.7173] } },
    { type: 'Feature', properties: { id: 'subway:723', system: 'subway', name: 'Grand Central-42 St', routes: ['7'] }, geometry: { type: 'Point', coordinates: [-73.9761, 40.7513] } },
  ],
};

export function train(id: string, routeId: string, lon: number, lat: number, delaySec: number, nextStopName: string) {
  return {
    id: `subway:${id}`, system: 'subway', feedId: routeId === 'L' ? 'subway-l' : 'subway-1234567', routeId, tripId: id,
    direction: 'N', headsign: routeId === 'L' ? '8 Av' : 'Flushing-Main St', lat, lon, bearing: 290, speedMps: 9,
    delaySec, status: 'IN_TRANSIT', positionSource: 'INTERPOLATED', prevStopId: 'subway:L08N', nextStopId: 'subway:L06N',
    nextStopName, nextArrival: inMin(2), progress: 0.4, dataTimestamp: now(), freshness: 'LIVE',
  };
}

export const trains = () => [
  train('048000_L..N01R', 'L', -73.97, 40.724, 0, '1 Av'),
  train('048300_L..S01R', 'L', -73.93, 40.707, 240, 'Morgan Av'),
  train('048000_7..N', '7', -73.96, 40.751, 420, 'Vernon Blvd-Jackson Av'),
];

export const health = () => ({
  generatedAt: now(), mode: 'replay', staleAfterSeconds: 60,
  feeds: [
    { feedId: 'subway-1234567', system: 'subway', status: 'UP', ageSeconds: 7, consecutiveFailures: 0, replay: true, dataTimestamp: now() },
    { feedId: 'subway-l', system: 'subway', status: 'UP', ageSeconds: 4, consecutiveFailures: 0, replay: true, dataTimestamp: now() },
  ],
});

/**
 * A canned SSE response: one snapshot, then a diff that moves the trains, then the body ends.
 * EventSource reconnects afterwards and gets the same sequence again, which keeps the mocked map
 * alive for as long as the test runs.
 */
function streamBody(): string {
  const moved = trains().map((t, i) => ({ ...t, lon: t.lon + 0.004 * (i + 1), bearing: 280 }));
  return [
    `event: snapshot\nid: 1\ndata: ${JSON.stringify({ type: 'snapshot', seq: 1, generatedAt: now(), sentAt: now(), entities: trains() })}\n\n`,
    `event: health\nid: 1\ndata: ${JSON.stringify(health())}\n\n`,
    `event: diff\nid: 2\ndata: ${JSON.stringify({ type: 'diff', seq: 2, generatedAt: now(), sentAt: now(), upserts: moved, removes: [] })}\n\n`,
  ].join('');
}

export const departures = (stationId: string) => ({
  stationId, name: stationId === 'subway:L08' ? 'Bedford Av' : 'Station', system: 'subway', lat: 40.7173,
  lon: -73.9567, routes: ['L'], generatedAt: now(),
  departures: [
    { trainId: 'subway:048000_L..N01R', tripId: '048000_L..N01R', routeId: 'L', route: 'L', color: '#A7A9AC',
      headsign: '8 Av', direction: 'N', arrival: inMin(3), minutesAway: 3, delaySec: 0, track: '1', status: 'scheduled' },
    { trainId: 'subway:048300_L..S01R', tripId: '048300_L..S01R', routeId: 'L', route: 'L', color: '#A7A9AC',
      headsign: 'Canarsie-Rockaway Pkwy', direction: 'S', arrival: inMin(7), minutesAway: 7, delaySec: 240,
      track: '2', status: 'scheduled' },
  ],
});

export const trainDetail = (id: string) => ({
  train: trains().find((t) => t.id === id) ?? trains()[0],
  route: 'L', color: '#A7A9AC', textColor: '#000000',
  upcomingStops: [
    { stopId: 'subway:L06N', name: '1 Av', arrival: inMin(2), delaySec: 0, track: '1', skipped: false },
    { stopId: 'subway:L05N', name: '3 Av', arrival: inMin(4), delaySec: 0, track: '1', skipped: false },
    { stopId: 'subway:L03N', name: '14 St-Union Sq', arrival: inMin(6), delaySec: 60, track: '1', skipped: false },
  ],
});

const json = (route: Route, body: unknown) =>
  route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

/** Installs the mocks. Base tiles are blocked so the app uses its offline fallback style. */
export async function mockApi(page: Page) {
  await page.route('https://tiles.openfreemap.org/**', (r) => r.abort());
  await page.route('**/api/systems', (r) => json(r, systems));
  await page.route('**/api/lines**', (r) => json(r, lines));
  await page.route('**/api/stations', (r) => json(r, stations));
  await page.route('**/api/trains', (r) => json(r, { generatedAt: now(), count: 3, trains: trains() }));
  await page.route('**/api/feeds/health', (r) => json(r, health()));
  await page.route('**/api/stream/trains', (r) =>
    r.fulfill({ status: 200, contentType: 'text/event-stream', body: streamBody() }));
  await page.route(/\/api\/stations\/[^/]+\/departures/, (r) =>
    json(r, departures(decodeURIComponent(r.request().url().split('/stations/')[1].split('/')[0]))));
  await page.route(/\/api\/trains\/.+/, (r) =>
    json(r, trainDetail(decodeURIComponent(r.request().url().split('/trains/')[1]))));
  await page.route('**/api/stations/nearby**', (r) =>
    json(r, [{ stationId: 'subway:L08', name: 'Bedford Av', system: 'subway', lat: 40.7173, lon: -73.9567,
      distanceM: 42, routes: ['L'] }]));
}
