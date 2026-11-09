'use client';
import Link from 'next/link';
import { Activity, Cpu, Download, Wallet, Zap } from 'lucide-react';
import type { Dashboard } from '@/lib/types';
import { cn } from '@/lib/cn';

export function Counters({ data, loading }: { data: Dashboard | null; loading: boolean }) {
  const items = [
    { label: 'Online', value: data?.counters.online ?? 0, icon: Zap, href: '/victims?status=online', tone: 'text-ok' },
    { label: 'Equipos', value: data?.counters.total ?? 0, icon: Cpu, href: '/victims', tone: 'text-ink' },
    { label: 'Wallets', value: data?.counters.wallets ?? 0, icon: Wallet, href: '/victims?has=wallets', tone: 'text-brand' },
    { label: 'Con saldo', value: data?.counters.balances ?? 0, icon: Activity, href: '/events?kind=wallet_balance', tone: 'text-warn' },
    { label: 'Artefactos', value: data?.counters.artifacts ?? 0, icon: Download, href: '/victims', tone: 'text-ink' },
    { label: 'Tareas en cola', value: data?.counters.queued ?? 0, icon: Activity, href: '/tasks?status=queued', tone: 'text-warn' }
  ];
  return (
    <div className="grid grid-cols-6 gap-2">
      {items.map((item) => (
        <Link key={item.label} href={item.href} className="rounded-lg border border-line bg-panel p-3 transition-colors hover:border-line/80 hover:bg-panel/70">
          <div className="flex items-center justify-between text-muted">
            <span className="text-2xs uppercase tracking-wide">{item.label}</span>
            <item.icon className="h-3.5 w-3.5" />
          </div>
          <p className={cn('num mt-2 text-2xl font-semibold', loading ? 'text-muted' : item.tone)}>
            {loading ? '—' : item.value.toLocaleString('es-ES')}
          </p>
        </Link>
      ))}
    </div>
  );
}
