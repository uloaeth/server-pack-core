import type { Metadata, Viewport } from 'next';
import { IBM_Plex_Mono, IBM_Plex_Sans } from 'next/font/google';
import { Toaster } from 'sonner';
import { THEME_BOOTSTRAP } from '@/lib/theme';
import './globals.css';

/* Plex: instrumento de laboratorio. La sans lleva la interfaz, la mono los datos. */
const sans = IBM_Plex_Sans({ subsets: ['latin'], weight: ['400', '500', '600', '700'], variable: '--font-sans', display: 'swap' });
const mono = IBM_Plex_Mono({ subsets: ['latin'], weight: ['400', '500', '600'], variable: '--font-mono', display: 'swap' });

export const metadata: Metadata = {
  title: { default: 'SPC Console', template: '%s · SPC' },
  description: 'Consola de operación autoalojada',
  robots: { index: false, follow: false }
};

export const viewport: Viewport = { themeColor: '#0b0d10', width: 'device-width', initialScale: 1 };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="es" className={`${sans.variable} ${mono.variable} dark`} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_BOOTSTRAP }} />
        <link rel="preconnect" href="https://a.tile.openstreetmap.org" crossOrigin="anonymous" />
        <link rel="preconnect" href="https://b.tile.openstreetmap.org" crossOrigin="anonymous" />
        <link rel="preconnect" href="https://c.tile.openstreetmap.org" crossOrigin="anonymous" />
        <link rel="preload" as="image" href="/world.jpg" fetchPriority="high" />
      </head>
      <body className="min-h-screen overflow-x-hidden font-sans text-base">
        {children}
        <Toaster theme="system" position="bottom-right" closeButton richColors />
      </body>
    </html>
  );
}
