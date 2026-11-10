'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, isAbort } from '@/lib/api';

type Options = {
  pollMs?: number;
  /** Keep the previous payload while a new path loads (avoid empty flicker). */
  keepData?: boolean;
};

const RETRY_MS = [800, 2_000, 5_000];

export function useApi<T>(path: string | null, options?: Options) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(Boolean(path));
  const [stale, setStale] = useState(false);
  const generation = useRef(0);
  const abort = useRef<AbortController | null>(null);
  const mounted = useRef(true);
  const keepData = options?.keepData ?? false;
  const hadData = useRef(false);

  const load = useCallback(async (manual = false) => {
    const current = path;
    if (!current) return;
    const id = ++generation.current;
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    if (!manual) setLoading((value) => value || !keepData);
    for (let attempt = 0; ; attempt += 1) {
      try {
        const result = await api<T>(current, { signal: controller.signal });
        if (!mounted.current || id !== generation.current) return;
        setData(result);
        hadData.current = true;
        setError(null);
        setStale(false);
        setLoading(false);
        return;
      } catch (caught) {
        if (!mounted.current || id !== generation.current || isAbort(caught)) return;
        const apiError = caught instanceof ApiError ? caught : new ApiError(0, 'network', 'Sin conexión con el panel');
        const retriable = apiError.status === 0 || apiError.status === 429 || apiError.status >= 500;
        if (retriable && attempt < RETRY_MS.length && navigator.onLine !== false) {
          await new Promise((resolve) => setTimeout(resolve, RETRY_MS[attempt]));
          if (!mounted.current || id !== generation.current) return;
          continue;
        }
        setError(apiError);
        setStale(hadData.current);
        setLoading(false);
        return;
      }
    }
  }, [path, keepData]);

  useEffect(() => {
    mounted.current = true;
    generation.current += 1;
    if (!keepData) { setData(null); hadData.current = false; }
    setError(null);
    setStale(false);
    if (!path) {
      setLoading(false);
      return () => { mounted.current = false; abort.current?.abort(); };
    }
    setLoading(true);
    void load();

    let timer: ReturnType<typeof setTimeout> | null = null;
    let cancelled = false;
    const loop = () => {
      if (cancelled || !options?.pollMs) return;
      timer = setTimeout(async () => {
        if (document.visibilityState === 'visible') await load(true);
        loop();
      }, options.pollMs);
    };
    loop();

    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        if (timer) clearTimeout(timer);
        void load(true);
        loop();
      }
    };
    document.addEventListener('visibilitychange', onVisible);

    return () => {
      mounted.current = false;
      cancelled = true;
      if (timer) clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisible);
      abort.current?.abort();
    };
  }, [path, options?.pollMs, keepData]); // eslint-disable-line react-hooks/exhaustive-deps

  return { data, error, loading, stale, refresh: () => load(true), setData };
}

export function useDebounced<T>(value: T, ms = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return debounced;
}

export async function mutate<T>(path: string, method: string, body?: unknown): Promise<T> {
  return api<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body) });
}
