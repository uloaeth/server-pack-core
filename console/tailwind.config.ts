import type { Config } from 'tailwindcss';

const config: Config = {
  darkMode: ['class'],
  content: ['./src/**/*.{ts,tsx}'],
  theme: {
    container: { center: true, padding: '1rem' },
    extend: {
      fontFamily: {
        sans: ['var(--font-sans)', 'ui-sans-serif', 'system-ui'],
        mono: ['var(--font-mono)', 'ui-monospace', 'SFMono-Regular', 'monospace']
      },
      colors: {
        bg: 'hsl(var(--bg))',
        panel: 'hsl(var(--panel))',
        line: 'hsl(var(--line))',
        ink: 'hsl(var(--ink))',
        muted: 'hsl(var(--muted))',
        brand: 'hsl(var(--brand))',
        ok: 'hsl(var(--ok))',
        warn: 'hsl(var(--warn))',
        bad: 'hsl(var(--bad))'
      },
      borderRadius: { lg: '8px', md: '6px', sm: '4px' },
      fontSize: {
        '2xs': ['10px', '14px'],
        xs: ['11px', '16px'],
        sm: ['12px', '18px'],
        base: ['13px', '20px']
      },
      boxShadow: { panel: '0 1px 0 0 hsl(var(--line)), 0 12px 32px -24px #000' },
      keyframes: {
        pulseRing: { '0%': { opacity: '0.8' }, '100%': { opacity: '0', transform: 'scale(2.2)' } },
        shimmer: { '100%': { transform: 'translateX(100%)' } }
      },
      animation: { pulseRing: 'pulseRing 1.8s ease-out infinite', shimmer: 'shimmer 1.4s infinite' }
    }
  },
  plugins: [require('tailwindcss-animate')]
};
export default config;
