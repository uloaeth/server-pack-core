'use client';
import { Suspense, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import QRCode from 'qrcode';
import { api, ApiError } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { SpiderLogo } from '@/components/spider-logo';
import { ThemeToggle } from '@/components/theme-toggle';

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
        setQr(await QRCode.toDataURL(result.uri!, { margin: 1, width: 220, color: { dark: '#e6e9ec', light: '#101216' } }));
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
        const fallback = caught instanceof ApiError ? caught.message : 'No se pudo entrar';
        setError(
          /authentication required|unauthorized/i.test(fallback)
            ? 'Correo o contraseña incorrectos.'
            : caught instanceof ApiError && caught.status === 429
              ? 'Demasiados intentos. Espera un minuto y vuelve a probar.'
              : fallback
        );
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
    <main className="grid-bg grid min-h-screen place-items-center bg-bg p-4">
      <section data-xp-login="" className="w-full max-w-[380px]">
        <div className="xp-login-top">
          <span className="xp-login-mark" aria-hidden="true"><SpiderLogo /></span>
          <span>SPC / OPS</span>
          <span className="xp-login-ver">acceso restringido</span>
          <ThemeToggle />
        </div>

        <div className="xp-login-brand">
          <p className="xp-login-kicker">Consola de operación</p>
          <h1 className="xp-login-title">Entra en el panel</h1>
          <p className="xp-login-lede">Sesión de operador con segundo factor obligatorio.</p>
        </div>

        <div className="xp-login-body">
          {expired ? <p className="xp-login-notice">La sesión caducó. Vuelve a entrar; te devolvemos a donde estabas.</p> : null}
          {step === 'password' ? (
            <form onSubmit={submitPassword} className="space-y-3">
              <div>
                <Label htmlFor="email">Correo</Label>
                <Input id="email" type="email" autoComplete="username" required autoFocus value={email} onChange={(event) => setEmail(event.target.value)} />
              </div>
              <div>
                <Label htmlFor="password">Contraseña</Label>
                <Input id="password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} />
              </div>
              {error ? <p className="xp-login-error" role="alert">{error}</p> : null}
              <Button className="w-full" size="lg" loading={busy}>Continuar</Button>
            </form>
          ) : null}

          {step === 'totp' ? (
            <form onSubmit={submitPassword} className="space-y-3">
              <p className="text-xs text-muted">El segundo factor es obligatorio. Código para <span className="font-mono text-ink">{email}</span></p>
              <div>
                <Label htmlFor="code">TOTP</Label>
                <Input id="code" inputMode="numeric" autoComplete="one-time-code" maxLength={8} required autoFocus value={code} onChange={(event) => setCode(event.target.value)} />
              </div>
              {error ? <p className="xp-login-error" role="alert">{error}</p> : null}
              <Button className="w-full" size="lg" loading={busy}>Verificar</Button>
            </form>
          ) : null}

          {step === 'setup' ? (
            <form onSubmit={enableTotp} className="space-y-3">
              <p className="text-xs text-muted">Primer acceso: escanea y confirma un código para activar TOTP.</p>
              {qr ? <img src={qr} alt="TOTP QR" className="xp-qr" /> : null}
              <p className="xp-login-secret">{secret}</p>
              <div>
                <Label htmlFor="setup-code">Código</Label>
                <Input id="setup-code" inputMode="numeric" maxLength={8} required autoFocus value={code} onChange={(event) => setCode(event.target.value)} />
              </div>
              {error ? <p className="xp-login-error" role="alert">{error}</p> : null}
              <Button className="w-full" size="lg" loading={busy}>Activar y entrar</Button>
            </form>
          ) : null}
        </div>

        <div className="xp-login-status">
          <span>argon2id</span>
          <span aria-hidden="true">·</span>
          <span>TOTP</span>
          <span aria-hidden="true">·</span>
          <span>SameSite=Strict</span>
          <span className="ml-auto">SPC v1</span>
        </div>
      </section>
    </main>
  );
}

export default function LoginPage() {
  return <Suspense fallback={<main className="grid-bg grid min-h-screen place-items-center bg-bg p-6"><p className="text-xs text-muted">Cargando…</p></main>}><LoginForm /></Suspense>;
}
