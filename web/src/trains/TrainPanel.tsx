import { useEffect, useState } from 'react';
import { api } from '../shared/api';
import { DataTable, type Column } from '../shared/DataTable';
import { delayClass, delayLabel, minutesUntil } from '../shared/format';
import type { TrainDetail, TrainState, UpcomingStop } from '../shared/types';

const SOURCE_NOTE: Record<string, string> = {
  GPS: 'reported by the train',
  INTERPOLATED: 'estimated from arrival predictions',
  NEXT_STOP: 'drawn at its next station',
};

const stopColumns = (nowMs: number): Column<UpcomingStop>[] => [
  { key: 'stop', header: 'Stop', cell: (s) => s.name },
  { key: 'track', header: 'Track', cell: (s) => (s.track ? <span className="muted">trk {s.track}</span> : null) },
  { key: 'in', header: 'In', align: 'right', cell: (s) => (s.skipped ? 'skipped' : minutesUntil(s.arrival, nowMs)) },
];

/** Detail of one train: route, delay, and the stops it still has to make. */
export function TrainPanel({ train, nowMs, onClose }: { train: TrainState; nowMs: number; onClose: () => void }) {
  const [detail, setDetail] = useState<TrainDetail | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const ctrl = new AbortController();
    setDetail(null);
    setFailed(false);
    api.train(train.id, ctrl.signal)
      .then(setDetail)
      .catch((e: Error) => {
        if (e.name !== 'AbortError') setFailed(true);
      });
    return () => ctrl.abort();
  }, [train.id]);

  const route = detail?.route ?? train.routeId ?? '?';
  return (
    <aside className="panel" data-testid="train-panel">
      <button className="close" onClick={onClose} aria-label="Close">
        ×
      </button>
      <h2>
        <span className="bullet" style={{ background: detail?.color ?? '#808183', color: detail?.textColor ?? '#fff' }}>
          {route}
        </span>
        to {train.headsign ?? '—'}
      </h2>
      <p className={`delay ${delayClass(train.delaySec)}`} data-testid="train-delay">
        {delayLabel(train.delaySec)}
        {train.freshness !== 'LIVE' && <span className="stale-tag"> · data is stale</span>}
      </p>
      <dl className="facts">
        <dt>Next stop</dt>
        <dd>
          {train.nextStopName ?? '—'} · {minutesUntil(train.nextArrival, nowMs)}
        </dd>
        <dt>Status</dt>
        <dd>{train.status.replace(/_/g, ' ').toLowerCase()}</dd>
        <dt>Position</dt>
        <dd title="Underground trains have no GPS; see ADR 1">{SOURCE_NOTE[train.positionSource]}</dd>
        {train.trainId && (
          <>
            <dt>Train</dt>
            <dd className="mono">{train.trainId}</dd>
          </>
        )}
      </dl>
      <h3>Remaining stops</h3>
      {failed ? (
        <p className="muted">Could not load this train's stops.</p>
      ) : detail == null ? (
        <p className="muted">Loading…</p>
      ) : detail.upcomingStops.length === 0 ? (
        <p className="muted">No further predictions in the feed.</p>
      ) : (
        <DataTable
          testId="train-stops"
          columns={stopColumns(nowMs)}
          rows={detail.upcomingStops}
          rowKey={(s) => s.stopId}
          rowClassName={(s) => (s.skipped ? 'skipped' : undefined)}
        />
      )}
    </aside>
  );
}
