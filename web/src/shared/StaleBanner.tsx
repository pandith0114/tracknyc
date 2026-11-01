import type { FeedsHealthResponse } from './types';

const LABEL: Record<string, string> = { subway: 'Subway', lirr: 'LIRR', path: 'PATH' };

/**
 * Warns when any enabled feed's data is older than the server's stale threshold (60 s by
 * default) or the feed is down — so users never mistake frozen trains for live ones.
 */
export function StaleBanner({ health, connectionLost }: { health: FeedsHealthResponse | null; connectionLost?: boolean }) {
  if (connectionLost) {
    return (
      <div className="stale-banner" role="alert" data-testid="stale-banner">
        Lost connection to TrackNYC — positions are not updating. Reconnecting…
      </div>
    );
  }
  if (!health) return null;
  const bad = health.feeds.filter((f) => f.status === 'STALE' || f.status === 'DOWN');
  if (bad.length === 0) return null;
  const systems = [...new Set(bad.map((f) => LABEL[f.system] ?? f.system))];
  return (
    <div className="stale-banner" role="alert" data-testid="stale-banner">
      {systems.join(', ')} data is more than {health.staleAfterSeconds} s old or unavailable — those trains may not be
      where they appear. Other systems are unaffected.
    </div>
  );
}
