'use client';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import {
  Activity, Boxes, Bug, ClipboardList, Cpu, FileClock, LayoutDashboard, LogOut, Network,
  ScrollText, Settings, ShieldCheck, Users, Wallet
} from 'lucide-react';
import { toast } from 'sonner';
import { cn } from '@/lib/cn';
import { mutate } from '@/hooks/useApi';
import type { Role } from '@/lib/types';
import { Badge } from '@/components/ui/badge';

const nav = [
  { href: '/dashboard', label: 'Overview', icon: LayoutDashboard },
  { href: '/victims', label: 'Victims', icon: Cpu },
  { href: '/tasks', label: 'Tasks', icon: ClipboardList },
  { href: '/builds', label: 'Builds', icon: Boxes },
  { href: '/events', label: 'Events', icon: Activity },
  { href: '/logs', label: 'Logs', icon: FileClock },
  { href: '/audit', label: 'Audit', icon: ScrollText },
  { href: '/operators', label: 'Operators', icon: Users, admin: true },
  { href: '/settings', label: 'Settings', icon: Settings }
];

export function Sidebar({ email, role, version }: { email: string; role: Role; version: string }) {
  const pathname = usePathname();
  const router = useRouter();

  async function logout() {
    try {
      await mutate('/auth/logout', 'POST');
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : 'logout failed');
      return;
    }
    router.push('/login');
    router.refresh();
  }

  return (
    <aside className="fixed inset-y-0 left-0 z-30 flex w-[212px] flex-col border-r border-line bg-panel/40">
      <div className="flex h-12 items-center gap-2 border-b border-line px-3">
        <div className="grid h-6 w-6 place-items-center rounded bg-brand/15 text-brand">
          <ShieldCheck className="h-3.5 w-3.5" />
        </div>
        <div>
          <p className="text-xs font-semibold leading-none">SPC Console</p>
          <p className="mt-1 font-mono text-2xs text-muted">v{version}</p>
        </div>
      </div>

      <nav className="flex-1 space-y-0.5 overflow-y-auto p-2">
        <p className="px-2 py-2 text-2xs uppercase tracking-widest text-muted/70">Operations</p>
        {nav.map((item) => {
          if (item.admin && role !== 'admin') return null;
          const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                'flex h-8 items-center gap-2 rounded px-2 text-xs text-muted transition-colors hover:bg-line/50 hover:text-ink',
                active && 'bg-brand/10 text-brand'
              )}
            >
              <item.icon className="h-3.5 w-3.5" />
              {item.label}
            </Link>
          );
        })}
        <p className="px-2 pb-1 pt-4 text-2xs uppercase tracking-widest text-muted/70">Material</p>
        <Link href="/victims?has=wallets" className="flex h-8 items-center gap-2 rounded px-2 text-xs text-muted hover:bg-line/50 hover:text-ink">
          <Wallet className="h-3.5 w-3.5" /> Wallets
        </Link>
        <Link href="/seeds" className="flex h-8 items-center gap-2 rounded px-2 text-xs text-muted hover:bg-line/50 hover:text-ink">
          <Bug className="h-3.5 w-3.5" /> Seeds
        </Link>
        <Link href="/events?kind=enrolled" className="flex h-8 items-center gap-2 rounded px-2 text-xs text-muted hover:bg-line/50 hover:text-ink">
          <Network className="h-3.5 w-3.5" /> Enrollments
        </Link>
      </nav>

      <div className="border-t border-line p-2">
        <div className="flex items-center justify-between rounded px-2 py-1.5">
          <div className="min-w-0">
            <p className="truncate text-2xs text-muted">{email}</p>
            <Badge tone={role === 'admin' ? 'brand' : role === 'operator' ? 'ok' : 'default'} className="mt-1">{role}</Badge>
          </div>
          <button onClick={logout} title="Sign out" className="rounded p-1.5 text-muted hover:bg-line hover:text-bad">
            <LogOut className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>
    </aside>
  );
}
