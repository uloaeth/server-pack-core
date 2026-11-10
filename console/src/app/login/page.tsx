'use client';
import { Suspense, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import QRCode from 'qrcode';
import { ShieldCheck } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';

type Step = 'password' | 'setup' | 'totp';

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const nextParam = params.get('next');
  const next = nextParam && nextParam.startsWith('/') ? nextParam : '/victims';
  const expired = params.get('expired') === '1';
  const [step, setStep] = useState<Step>('password');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [secret, setSecret] = useState('');
  const [qr, setQr] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submitPassword(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const result = await api<{ setup?: boolean; secret?: string; uri?: string; totp_required?: boolean }>('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password, ...(step === 'totp' ? { totp: code } : {}) })
      });
      if (result.setup) {
        setSecret(result.secret!);
        setQr(await QRCode.toDataURL(result.uri!, { margin: 1, width: 220, color: { dark: '#e6e8eb', light: '#101216' } }));
        setStep('setup');
      } else {
        router.push(next);
        router.refresh();
      }
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'totp_required') {
        setStep('totp');
        setError('Introduce el código de 6 dígitos de tu autenticador.');
      } else {
        setError(caught instanceof ApiError ? caught.message : 'No se pudo entrar');
      }
    } finally {
      setBusy(false);
    }
  }

  async function enableTotp(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await api('/auth/totp/enable', { method: 'POST', body: JSON.stringify({ code }) });
      router.push('/victims');
      router.refresh();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Código no válido');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="grid min-h-screen place-items-center bg-bg p-6 grid-bg">
      <div className="w-full max-w-[380px] rounded-xl border border-line bg-panel p-6 shadow-panel">
        <div className="mb-5 flex items-center gap-2">
          <div className="grid h-9 w-9 place-items-center rounded-lg bg-brand/15 text-brand">
            <ShieldCheck className="h-4 w-4" />
          </div>
          <div>
            <h1 className="text-sm font-semibold">SPC Console</h1>
            <p className="font-mono text-2xs text-muted">acceso restringido</p>
          </div>
        </div>

        {expired ? <p className="mb-3 rounded border border-warn/40 bg-warn/10 p-2 text-2xs text-warn">La sesión caducó. Vuelve a entrar; te devolvemos a donde estabas.</p> : null}
        {step === 'password' ? (
          <form onSubmit={submitPassword} className="space-y-3">
            <div>
              <Label htmlFor="email">Correo</Label>
              <Input id="email" type="email" autoComplete="username" required value={email} onChange={(event) => setEmail(event.target.value)} />
            </div>
            <div>
              <Label htmlFor="password">Contraseña</Label>
              <Input id="password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} />
            </div>
            {error ? <p className="rounded border border-bad/40 bg-bad/10 p-2 font-mono text-2xs text-bad">{error}</p> : null}
            <Button className="w-full" size="lg" loading={busy}>Continuar</Button>
          </form>
        ) : null}

        {step === 'totp' ? (
          <form onSubmit={submitPassword} className="space-y-3">
            <p className="text-xs text-muted">El segundo factor es obligatorio. Código para <span className="font-mono">{email}</span></p>
            <div>
              <Label htmlFor="code">TOTP</Label>
              <Input id="code" inputMode="numeric" autoComplete="one-time-code" maxLength={8} required autoFocus value={code} onChange={(event) => setCode(event.target.value)} />
            </div>
            {error ? <p className="rounded border border-bad/40 bg-bad/10 p-2 text-2xs text-bad">{error}</p> : null}
            <Button className="w-full" size="lg" loading={busy}>Verificar</Button>
          </form>
        ) : null}

        {step === 'setup' ? (
          <form onSubmit={enableTotp} className="space-y-3">
            <p className="text-xs text-muted">Primer acceso: escanea y confirma un código para activar TOTP.</p>
            {qr ? <img src={qr} alt="TOTP QR" className="mx-auto rounded border border-line" /> : null}
            <p className="break-all rounded border border-line bg-bg p-2 font-mono text-2xs">{secret}</p>
            <div>
              <Label htmlFor="setup-code">Código</Label>
              <Input id="setup-code" inputMode="numeric" maxLength={8} required autoFocus value={code} onChange={(event) => setCode(event.target.value)} />
            </div>
            {error ? <p className="rounded border border-bad/40 bg-bad/10 p-2 text-2xs text-bad">{error}</p> : null}
            <Button className="w-full" size="lg" loading={busy}>Activar y entrar</Button>
          </form>
        ) : null}

        <p className="mt-5 text-center text-2xs text-muted">argon2id · TOTP · SameSite=Strict</p>
      </div>
    </main>
  );
}

export default function LoginPage() {
  return <Suspense fallback={<main className="grid min-h-screen place-items-center bg-bg p-6 grid-bg"><p className="text-xs text-muted">Cargando…</p></main>}><LoginForm /></Suspense>;
}
