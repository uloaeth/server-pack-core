'use client';
import { useEffect, useState } from 'react';
import { Moon, Sun } from 'lucide-react';
import { applyTheme, readTheme, type Theme } from '@/lib/theme';

/** Conmutador del tema XP claro / XP oscuro. Mantiene exactamente la estructura. */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>('xp');

  useEffect(() => setTheme(readTheme()), []);

  const toggle = () => {
    const next: Theme = theme === 'xp-dark' ? 'xp' : 'xp-dark';
    setTheme(next);
    applyTheme(next);
  };

  return (
    <button
      type="button"
      className="xp-theme-toggle"
      onClick={toggle}
      aria-label={theme === 'xp-dark' ? 'Cambiar a tema XP claro' : 'Cambiar a tema XP oscuro'}
      title={theme === 'xp-dark' ? 'Tema XP claro' : 'Tema XP oscuro'}
    >
      {theme === 'xp-dark' ? <Sun /> : <Moon />}
      <span className="hidden sm:inline">{theme === 'xp-dark' ? 'Claro' : 'Oscuro'}</span>
    </button>
  );
}
