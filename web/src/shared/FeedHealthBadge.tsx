import type { FeedHealth, FeedsHealthResponse } from './types';
import { ageLabel } from './format';

const LABEL: Record<string, string> = { subway: 'Subway', lirr: 'LIRR', path: 'PATH' };

function systemStatus(feeds: FeedHealth[]): FeedHealth['status'] {
  const order: FeedHealth['status'][] = ['UP', 'DEGRADED', 'STALE', 'DOWN', 'DISABLED'];
  const active = feeds.filter((f) => f.status !== 'DISABLED');
  if (active.length === 0) return 'DISABLED';
  return active.reduce((worst, f) => (order.indexOf(f.status) > order.indexOf(worst) ? f.status : worst), 'UP' as FeedHealth['status']);
}

/** "Subway · updated 8 s ago" per system, with a coloured status dot and per-feed tooltip. */
export function FeedHealthBadge({ health, nowMs }: { health: FeedsHealthResponse | null; nowMs: number }) {
  if (!health) {
    return <div className="feed-health" data-testid="feed-health">connecting…</div>;
  }
  const bySystem = new Map<string, FeedHealth[]>();
  for (const f of health.feeds) {
    bySystem.set(f.system, [...(bySystem.get(f.system) ?? []), f]);
  }
  const fetchedAgo = (nowMs - Date.parse(health.generatedAt)) / 1000;
  return (
    <div className="feed-health" data-testid="feed-health">
      {health.mode === 'replay' && (
        <span className="badge replay" title="Playing recorded, synthetic fixture data on a loop — not live MTA data">
          REPLAY
        </span>
      )}
      {[...bySystem.entries()].map(([system, feeds]) => {
        const status = systemStatus(feeds);
        const newest = Math.min(...feeds.map((f) => f.ageSeconds ?? Number.POSITIVE_INFINITY));
        const age = Number.isFinite(newest) ? newest + Math.max(0, fetchedAgo) : null;
        const tip = feeds
          .map((f) => `${f.feedId}: ${f.status}${f.ageSeconds != null ? `, ${ageLabel(f.ageSeconds)}` : ''}${f.lastError ? ` (${f.lastError})` : ''}`)
          .join('\n');
        return (
          <span key={system} className={`badge status-${status.toLowerCase()}`} title={tip} data-testid={`feed-${system}`}>
            <span className="dot" />
            {LABEL[system] ?? system} · {status === 'DISABLED' ? 'off' : status === 'DOWN' ? 'down' : `updated ${ageLabel(age)}`}
          </span>
        );
      })}
    </div>
  );
}
