'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError } from '@/lib/api';

export function useApi<T>(path: string | null, options?: { pollMs?: number }) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(Boolean(path));
  const alive = useRef(true);

  const load = useCallback(async () => {
    if (!path) return;
    try {
      const result = await api<T>(path);
      if (alive.current) {
        setData(result);
        setError(null);
      }
    } catch (caught) {
      if (alive.current) setError(caught instanceof ApiError ? caught : new ApiError(0, 'network', String(caught)));
    } finally {
      if (alive.current) setLoading(false);
    }
  }, [path]);

  useEffect(() => {
    alive.current = true;
    setLoading(true);
    void load();
    if (!options?.pollMs) return () => { alive.current = false; };
    const timer = setInterval(() => void load(), options.pollMs);
    return () => {
      alive.current = false;
      clearInterval(timer);
    };
  }, [load, options?.pollMs]);

  return { data, error, loading, refresh: load, setData };
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
