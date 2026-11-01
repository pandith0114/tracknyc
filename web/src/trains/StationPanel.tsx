import { useMemo, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { api } from '../shared/api';
import { DataTable, type Column } from '../shared/DataTable';
import { delayClass, delayLabel, minutesUntil } from '../shared/format';
import { usePolling } from '../shared/usePolling';
import type { Departure } from '../shared/types';

const REFRESH_MS = 15_000;

/** Stops a click or Enter on a route badge from also selecting the train in that row. */
const isolate = (e: MouseEvent | KeyboardEvent) => e.stopPropagation();

/**
 * Live departure board of a station ("Q to Coney Island – 3 min"). Clicking a route, either in
 * the chips above the board or on a badge in a row, narrows the board to that route.
 * The parent keys this panel by station, so switching stations starts with a fresh board.
 */
export function StationPanel({
  stationId, nowMs, onSelectTrain, onClose,
}: {
  stationId: string;
  nowMs: number;
  onSelectTrain: (trainId: string) => void;
  onClose: () => void;
}) {
  const { data: board, error } = usePolling((signal) => api.station(stationId, signal), REFRESH_MS);
  const [route, setRoute] = useState<string | null>(null);

  const departures = board?.departures ?? [];
  const lines = useMemo(() => {
    const seen = new Map<string, { route: string; color: string }>();
    for (const d of departures) if (!seen.has(d.route)) seen.set(d.route, { route: d.route, color: d.color });
    return [...seen.values()];
  }, [departures]);
  const shown = route ? departures.filter((d) => d.route === route) : departures;

  const toggleRoute = (r: string) => setRoute((current) => (current === r ? null : r));

  const columns: Column<Departure>[] = [
    {
      key: 'line',
      header: 'Line',
      cell: (d) => (
        <>
          <button
            type="button"
            className="bullet route-button"
            style={{ background: d.color }}
            aria-pressed={route === d.route}
            aria-label={`Show only ${d.route} trains`}
            onClick={(e) => {
              isolate(e);
              toggleRoute(d.route);
            }}
            onKeyDown={isolate}
          >
            {d.route}
          </button>
          {d.track && <span className="track">trk {d.track}</span>}
        </>
      ),
    },
    { key: 'to', header: 'To', cell: (d) => d.headsign ?? d.direction ?? '—' },
    { key: 'in', header: 'In', align: 'right', cell: (d) => minutesUntil(d.arrival, nowMs) },
    {
      key: 'status',
      header: 'Status',
      cell: (d) => <span className={`delay ${delayClass(d.delaySec)}`}>{delayLabel(d.delaySec)}</span>,
    },
  ];

  let body;
  if (board == null && error) body = <p className="muted">Could not load departures.</p>;
  else if (board == null) body = <p className="muted">Loading…</p>;
  else if (departures.length === 0) body = <p className="muted">No trains due in the next hour.</p>;
  else if (shown.length === 0) body = <p className="muted">No upcoming {route} trains in the next hour.</p>;
  else
    body = (
      <DataTable
        testId="departures"
        columns={columns}
        rows={shown}
        rowKey={(d) => `${d.tripId}-${d.arrival}`}
        onRowClick={(d) => onSelectTrain(d.trainId)}
      />
    );

  return (
    <aside className="panel station" data-testid="station-panel">
      <button className="close" onClick={onClose} aria-label="Close">
        ×
      </button>
      <h2>{board?.name ?? 'Station'}</h2>
      {lines.length > 0 && (
        <div className="route-filters" role="group" aria-label="Filter by line">
          <button
            type="button"
            className="chip"
            aria-pressed={route === null}
            onClick={() => setRoute(null)}
          >
            All
          </button>
          {lines.map((l) => (
            <button
              key={l.route}
              type="button"
              className="bullet route-button"
              style={{ background: l.color }}
              aria-pressed={route === l.route}
              aria-label={`Show only ${l.route} trains`}
              onClick={() => toggleRoute(l.route)}
            >
              {l.route}
            </button>
          ))}
        </div>
      )}
      {body}
      <p className="muted small">Predictions come from the feed and may be wrong. Click a row to follow that train.</p>
    </aside>
  );
}
