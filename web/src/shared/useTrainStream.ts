import { useEffect, useRef, useState } from 'react';
import { API_BASE } from './api';
import type { FeedHealth, TrainState } from './types';

/** A `diff` event: entities that changed plus ids that disappeared. */
interface DiffMessage {
  type: 'diff';
  seq: number;
  generatedAt: string;
  sentAt: string;
  upserts: TrainState[];
  removes: string[];
}

interface SnapshotMessage {
  type: 'snapshot';
  seq: number;
  generatedAt: string;
  sentAt: string;
  entities: TrainState[];
}

export interface StreamState {
  /** Current trains by id. The map object identity changes on every applied message. */
  trains: Map<string, TrainState>;
  /** Server time the newest positions were computed, as epoch millis. */
  generatedAtMs: number;
  connected: boolean;
  /** Feed health pushed on the same stream. */
  health: FeedHealth[] | null;
  seq: number;
}

const INITIAL: StreamState = { trains: new Map(), generatedAtMs: 0, connected: false, health: null, seq: -1 };

/**
 * Subscribes to `/api/stream/trains`: a snapshot on connect, then diffs every few seconds.
 *
 * A gap in the sequence number means a message was lost, so the stream is restarted to get a
 * fresh snapshot rather than drawing a map that silently drifts out of date. EventSource already
 * reconnects by itself when the connection drops.
 */
export function useTrainStream(enabled = true): StreamState {
  const [state, setState] = useState<StreamState>(INITIAL);
  const reconnectRef = useRef(0);

  useEffect(() => {
    if (!enabled) return;
    let source: EventSource | null = null;
    let restartTimer: ReturnType<typeof setTimeout> | undefined;

    const restart = (delayMs: number) => {
      source?.close();
      restartTimer = setTimeout(connect, delayMs);
    };

    const connect = () => {
      source = new EventSource(`${API_BASE}/api/stream/trains`);

      source.addEventListener('snapshot', (e) => {
        const msg = JSON.parse((e as MessageEvent<string>).data) as SnapshotMessage;
        const trains = new Map<string, TrainState>();
        for (const t of msg.entities) trains.set(t.id, t);
        reconnectRef.current = 0;
        setState((prev) => ({
          ...prev,
          trains,
          generatedAtMs: Date.parse(msg.generatedAt),
          connected: true,
          seq: msg.seq,
        }));
      });

      source.addEventListener('diff', (e) => {
        const msg = JSON.parse((e as MessageEvent<string>).data) as DiffMessage;
        setState((prev) => {
          if (prev.seq >= 0 && msg.seq !== prev.seq + 1) {
            // Missed a diff: reconnect for a fresh snapshot instead of applying it out of order.
            restart(250);
            return prev;
          }
          const trains = new Map(prev.trains);
          for (const id of msg.removes) trains.delete(id);
          for (const t of msg.upserts) trains.set(t.id, t);
          return { ...prev, trains, generatedAtMs: Date.parse(msg.generatedAt), connected: true, seq: msg.seq };
        });
      });

      source.addEventListener('health', (e) => {
        const msg = JSON.parse((e as MessageEvent<string>).data) as { feeds: FeedHealth[] };
        setState((prev) => ({ ...prev, health: msg.feeds }));
      });

      source.onerror = () => {
        setState((prev) => ({ ...prev, connected: false }));
        // EventSource retries on its own; this only backs off repeated hard failures (e.g. 503
        // when the gateway is at capacity, where the browser would otherwise hammer the server).
        if (source?.readyState === EventSource.CLOSED) {
          reconnectRef.current = Math.min(reconnectRef.current + 1, 5);
          restart(1000 * 2 ** reconnectRef.current);
        }
      };
    };

    connect();
    return () => {
      clearTimeout(restartTimer);
      source?.close();
    };
  }, [enabled]);

  return state;
}
