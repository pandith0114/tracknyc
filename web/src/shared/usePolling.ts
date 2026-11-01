import { useEffect, useRef, useState } from 'react';

/** Calls `load` now and every `intervalMs`; keeps the last good value and the last error. */
export function usePolling<T>(load: (signal: AbortSignal) => Promise<T>, intervalMs: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    let ctrl = new AbortController();
    const run = () => {
      ctrl.abort();
      ctrl = new AbortController();
      loadRef
        .current(ctrl.signal)
        .then((v) => {
          setData(v);
          setError(null);
        })
        .catch((e: unknown) => {
          if ((e as Error).name !== 'AbortError') setError(e as Error);
        });
    };
    run();
    const id = setInterval(run, intervalMs);
    return () => {
      clearInterval(id);
      ctrl.abort();
    };
  }, [intervalMs]);

  return { data, error };
}

/** Re-renders every `ms` so "updated 8 s ago" labels tick. */
export function useNow(ms = 1000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}
