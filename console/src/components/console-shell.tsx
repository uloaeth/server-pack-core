'use client';
import { useEffect, useState } from 'react';
import { usePathname } from 'next/navigation';
import { Menu } from 'lucide-react';
import { Sidebar } from '@/components/sidebar';
import { SpiderLogo } from '@/components/spider-logo';
import { Button } from '@/components/ui/button';
import type { Role } from '@/lib/types';
import pkg from '../../package.json';

export function ConsoleShell({ children, email, role }: { children: React.ReactNode; email: string; role: Role }) {
  const [open, setOpen] = useState(false);
  const pathname = usePathname();

  useEffect(() => setOpen(false), [pathname]);
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  return (
    <>
      <a href="#spc-main" className="sr-only z-50 rounded bg-panel px-3 py-2 text-xs focus:not-sr-only focus:fixed focus:left-2 focus:top-2">Saltar al contenido</a>
      <Sidebar email={email} role={role} version={pkg.version} open={open} onClose={() => setOpen(false)} />
      <div className="lg:ml-[232px] min-h-screen min-w-0">
        <div className="xp-mobilebar sticky top-0 z-30 flex h-12 items-center gap-2 border-b border-line/80 bg-bg/90 px-3 backdrop-blur-xl lg:hidden">
          <Button size="icon" variant="ghost" onClick={() => setOpen(true)} aria-label="Abrir menú" aria-expanded={open} aria-controls="spc-nav"><Menu className="h-4 w-4" /></Button>
          <SpiderLogo className="h-4 w-4 text-brand" />
          <span className="text-xs font-semibold tracking-tight">SPC Console</span>
        </div>
        <main id="spc-main" tabIndex={-1} className="xp-shell mx-auto min-w-0 max-w-[1760px] p-3 outline-none lg:p-5">{children}</main>
      </div>
    </>
  );
}
