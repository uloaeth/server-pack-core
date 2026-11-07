'use client';
import { useEffect } from 'react';

export function useHotkeys(map: Record<string, (event: KeyboardEvent) => void>) {
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
      const combo = [event.metaKey || event.ctrlKey ? 'mod' : '', event.key.toLowerCase()].filter(Boolean).join('+');
      const action = map[combo] ?? map[event.key.toLowerCase()];
      if (!action) return;
      if (typing && !combo.startsWith('mod')) return;
      event.preventDefault();
      action(event);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [map]);
}
