import { useEffect, useState } from "react";

export interface Loaded<T> {
  data: T | undefined;
  error: string | null;
  loading: boolean;
  reload: () => void;
}

/** Carica dati asincroni e scarta le risposte arrivate dopo un cambio di pagina. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[]): Loaded<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    fn().then(
      (d) => {
        if (!alive) return;
        setData(d);
        setLoading(false);
      },
      (e: unknown) => {
        if (!alive) return;
        setError(e instanceof Error ? e.message : String(e));
        setLoading(false);
      },
    );
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  return { data, error, loading, reload: () => setTick((t) => t + 1) };
}
