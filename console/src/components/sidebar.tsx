'use client';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import {
  Activity, Boxes, ClipboardList, Cpu, FileClock, LayoutDashboard, LogOut,
  ScrollText, Settings, ShieldCheck, Sparkles, Users, Wallet, X
} from 'lucide-react';
import { toast } from 'sonner';
import { cn } from '@/lib/cn';
import { mutate } from '@/hooks/useApi';
import type { Role } from '@/lib/types';
import { Badge } from '@/components/ui/badge';
import { ThemeToggle } from '@/components/theme-toggle';
import { Button } from '@/components/ui/button';

const nav = [
  { href: '/victims', label: 'Equipos', icon: Cpu },
  { href: '/dashboard', label: 'Resumen', icon: LayoutDashboard },
  { href: '/tasks', label: 'Tareas', icon: ClipboardList },
  { href: '/builds', label: 'Builds', icon: Boxes },
  { href: '/vault', label: 'Bóveda', icon: Wallet },
  { href: '/seeds', label: 'Semillas y claves', icon: Sparkles },
  { href: '/events', label: 'Eventos', icon: Activity },
  { href: '/logs', label: 'Logs', icon: FileClock },
  { href: '/audit', label: 'Auditoría', icon: ScrollText },
  { href: '/operators', label: 'Operadores', icon: Users, admin: true },
  { href: '/settings', label: 'Ajustes', icon: Settings }
];

export function Sidebar({
  email,
  role,
  version,
  open = false,
  onClose
}: {
  email: string;
  role: Role;
  version: string;
  open?: boolean;
  onClose?: () => void;
}) {
  const pathname = usePathname();
  const router = useRouter();

  async function logout() {
    try {
      await mutate('/auth/logout', 'POST');
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'No se pudo cerrar sesión');
      return;
    }
    router.push('/login');
    router.refresh();
  }

  return (
    <>
      {open ? <button className="fixed inset-0 z-30 bg-black/60 backdrop-blur-sm lg:hidden" aria-label="Cerrar menú" onClick={onClose} /> : null}
      <aside
        id="spc-nav"
        aria-label="Navegación principal"
        className={cn(
          'xp-sidebar fixed inset-y-0 left-0 z-40 flex w-[248px] flex-col border-r border-line/80 bg-panel/95 backdrop-blur-xl transition-transform duration-200 lg:w-[232px] lg:translate-x-0 lg:bg-panel/55',
          open ? 'translate-x-0 shadow-2xl' : '-translate-x-full'
        )}
      >
        <div className="xp-brand flex h-16 items-center gap-2.5 border-b border-line/80 px-4">
          <div className="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-br from-brand/30 to-brand/5 text-brand shadow-glow">
            <ShieldCheck className="h-4 w-4" />
          </div>
          <div className="min-w-0">
            <p className="text-sm font-semibold leading-none tracking-tight">SPC Console</p>
            <p className="mt-1 font-mono text-2xs text-muted/80">v{version}</p>
          </div>
          <Button size="icon" variant="ghost" className="ml-auto lg:hidden" onClick={onClose} aria-label="Cerrar menú"><X className="h-4 w-4" /></Button>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto p-3">
          <p className="px-2 pb-1 pt-2 text-2xs font-medium uppercase tracking-[0.16em] text-muted/60">Operación</p>
          {nav.map((item) => {
            if (item.admin && role !== 'admin') return null;
            const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
            return (
              <Link
                key={item.href}
                href={item.href}
                onClick={onClose}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'flex h-9 items-center gap-2.5 rounded-lg px-2.5 text-sm text-muted transition-colors hover:bg-panel-2 hover:text-ink',
                  active && 'bg-brand/12 text-brand shadow-[inset_0_0_0_1px_hsl(var(--brand)/0.18)]'
                )}
              >
                <item.icon className="h-4 w-4" />
                {item.label}
              </Link>
            );
          })}
        </nav>

        <div className="border-t border-line/80 p-3">
          <div className="flex items-center justify-between rounded px-2 py-1.5">
            <div className="min-w-0">
              <p className="truncate text-2xs text-muted">{email}</p>
              <Badge tone={role === 'admin' ? 'brand' : role === 'operator' ? 'ok' : 'default'} className="mt-1">{role}</Badge>
            </div>
            <div className="flex items-center gap-1">
              <ThemeToggle />
              <button onClick={logout} title="Cerrar sesión" aria-label="Cerrar sesión" className="rounded p-1.5 text-muted hover:bg-line hover:text-bad">
                <LogOut className="h-4 w-4" />
              </button>
            </div>
          </div>
        </div>
      </aside>
    </>
  );
}
