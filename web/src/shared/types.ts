// Wire types of the TrackNYC API. Instants are ISO-8601 strings.

export type Freshness = 'LIVE' | 'STALE' | 'MISSING';
export type TrainStatus = 'AT_STATION' | 'IN_TRANSIT' | 'WAITING_AT_ORIGIN';

export interface TrainState {
  id: string;
  system: string;
  feedId: string;
  routeId?: string;
  tripId: string;
  trainId?: string;
  direction?: string;
  headsign?: string;
  lat: number;
  lon: number;
  bearing: number;
  speedMps: number;
  delaySec?: number;
  status: TrainStatus;
  positionSource: 'GPS' | 'INTERPOLATED' | 'NEXT_STOP';
  prevStopId?: string;
  nextStopId?: string;
  nextStopName?: string;
  nextArrival?: string;
  progress: number;
  dataTimestamp?: string;
  freshness: Freshness;
}

export type FeedStatus = 'UP' | 'DEGRADED' | 'STALE' | 'DOWN' | 'DISABLED';

export interface FeedHealth {
  feedId: string;
  system: string;
  status: FeedStatus;
  lastSuccessAt?: string;
  dataTimestamp?: string;
  ageSeconds?: number;
  consecutiveFailures: number;
  lastError?: string;
  circuitState?: string;
  replay: boolean;
}

export interface FeedsHealthResponse {
  generatedAt: string;
  mode: 'replay' | 'live';
  staleAfterSeconds: number;
  feeds: FeedHealth[];
}

export interface RouteView {
  system: string;
  id: string;
  publicId: string;
  shortName: string;
  longName?: string;
  color: string;
  textColor: string;
}

export interface SystemView {
  id: string;
  displayName: string;
  attribution?: string;
  replay: boolean;
  bounds?: [number, number, number, number];
  routes: RouteView[];
}

export interface TrainsResponse {
  generatedAt: string;
  count: number;
  trains: TrainState[];
}

export interface Departure {
  trainId: string;
  tripId: string;
  routeId?: string;
  route: string;
  color: string;
  headsign?: string;
  direction?: string;
  arrival: string;
  minutesAway: number;
  delaySec?: number;
  track?: string;
  status: string;
}

export interface DepartureBoard {
  stationId: string;
  name: string;
  system: string;
  routes: string[];
  generatedAt: string;
  departures: Departure[];
}

export interface UpcomingStop {
  stopId: string;
  name: string;
  arrival?: string;
  delaySec?: number;
  track?: string;
  skipped: boolean;
}

export interface TrainDetail {
  train: TrainState;
  route: string;
  color: string;
  textColor: string;
  upcomingStops: UpcomingStop[];
}

export interface NearbyStation {
  stationId: string;
}
