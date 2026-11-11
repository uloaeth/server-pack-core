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
    { label: 'Tareas en cola', value: data?.counters.queued ?? 0, icon: Activity, href: '/tasks?status=queued', tone: 'text-warn' },
    { label: 'Errores 24 h', value: data?.counters.failures ?? 0, icon: Activity, href: '/events', tone: 'text-bad' }
  ];
  return (
    <section data-xp-meter="" aria-label="Métricas de la flota" className="grid grid-cols-2 overflow-hidden sm:grid-cols-4 xl:grid-cols-7">
      {items.map((item, index) => (
        <Link
          key={item.label}
          href={item.href}
          className={cn('xp-meter-cell focus-ring', index === items.length - 1 && 'col-span-2 xl:col-span-1')}
        >
          <span className="flex items-center justify-between gap-2 text-muted">
            <span className="xp-meter-label">{item.label}</span>
            <item.icon className="h-3 w-3 shrink-0" aria-hidden="true" />
          </span>
          <span className={cn('xp-meter-value', loading ? 'text-muted' : item.tone)}>
            {loading ? '—' : item.value.toLocaleString('es-ES')}
          </span>
        </Link>
      ))}
    </section>
  );
}
