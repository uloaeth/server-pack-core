'use client';

/**
 * Tema visual. Solo hay dos variantes, ambas con la misma estructura XP:
 * `xp` (Luna claro, por defecto) y `xp-dark` (misma densidad, paleta oscura).
 * Todo el color vive en tokens de `globals.css`; aquí solo se cambia el
 * atributo `data-theme` del documento y se persiste la elección.
 */
export type Theme = 'xp' | 'xp-dark';

const KEY = 'spc.theme';

export function readTheme(): Theme {
  if (typeof document === 'undefined') return 'xp';
  const stored = document.documentElement.dataset.theme;
  return stored === 'xp-dark' ? 'xp-dark' : 'xp';
}

export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(KEY, theme);
  } catch {
    /* almacenamiento bloqueado: el tema sigue aplicado en esta sesión */
  }
}

/** Script previo al paint para evitar el destello del tema moderno. */
export const THEME_BOOTSTRAP = `(function(){try{var t=localStorage.getItem('${KEY}');document.documentElement.dataset.theme=t==='xp-dark'?'xp-dark':'xp';}catch(e){document.documentElement.dataset.theme='xp';}})();`;
