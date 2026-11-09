import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { Sidebar } from '@/components/sidebar';
import { LiveProvider } from '@/components/live-provider';
import type { Operator } from '@/lib/types';

export const dynamic = 'force-dynamic';

async function me(): Promise<Operator | null> {
  const origin = process.env.API_ORIGIN ?? 'http://127.0.0.1:4000';
  const cookie = cookies().toString();
  const response = await fetch(`${origin}/api/console/auth/me`, { headers: { cookie }, cache: 'no-store' });
  if (!response.ok) return null;
  return (await response.json()) as Operator;
}

export default async function ConsoleLayout({ children }: { children: React.ReactNode }) {
  const operator = await me();
  if (!operator) redirect('/login');
  return (
    <LiveProvider role={operator.role}>
      <Sidebar email={operator.email} role={operator.role} version="1.0.0" />
      <div className="ml-[232px] min-h-screen min-w-0">
        <main className="mx-auto min-w-0 max-w-[1760px] p-4 lg:p-5">{children}</main>
      </div>
    </LiveProvider>
  );
}
