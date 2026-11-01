import type { FeatureCollection } from 'geojson';
import type {
  DepartureBoard, FeedsHealthResponse, NearbyStation, SystemView, TrainDetail, TrainsResponse,
} from './types';

/** Base URL of the API; same origin by default (nginx / Vite proxy forward /api). */
export const API_BASE: string = (import.meta.env.VITE_API_BASE as string | undefined) ?? '';

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, { signal, headers: { Accept: 'application/json' } });
  if (!res.ok) {
    throw new Error(`${path}: HTTP ${res.status}`);
  }
  return (await res.json()) as T;
}

export const api = {
  systems: (signal?: AbortSignal) => getJson<SystemView[]>('/api/systems', signal),
  lines: (signal?: AbortSignal) => getJson<FeatureCollection>('/api/lines', signal),
  stations: (signal?: AbortSignal) => getJson<FeatureCollection>('/api/stations', signal),
  trains: (signal?: AbortSignal) => getJson<TrainsResponse>('/api/trains', signal),
  feedHealth: (signal?: AbortSignal) => getJson<FeedsHealthResponse>('/api/feeds/health', signal),
  station: (id: string, signal?: AbortSignal) =>
    getJson<DepartureBoard>(`/api/stations/${encodeURIComponent(id)}/departures`, signal),
  train: (id: string, signal?: AbortSignal) => getJson<TrainDetail>(`/api/trains/${encodeURIComponent(id)}`, signal),
  nearbyStations: (lat: number, lon: number, signal?: AbortSignal) =>
    getJson<NearbyStation[]>(`/api/stations/nearby?lat=${lat}&lon=${lon}`, signal),
};
