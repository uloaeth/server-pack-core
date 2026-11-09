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
      borderRadius: { lg: '12px', md: '8px', sm: '6px' },
      fontSize: {
        '2xs': ['11px', '15px'],
        xs: ['12px', '17px'],
        sm: ['13px', '19px'],
        base: ['14px', '21px']
      },
      boxShadow: { panel: '0 1px 0 0 hsl(var(--line)), 0 24px 64px -32px #000', glow: '0 0 0 1px hsl(var(--brand) / 0.25), 0 12px 40px -12px hsl(var(--brand) / 0.25)' },
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
