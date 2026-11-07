'use client';
import { useState } from 'react';
import { useRouter } from 'next/navigation';
import QRCode from 'qrcode';
import { ShieldCheck } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';

type Step = 'password' | 'setup' | 'totp';

export default function LoginPage() {
  const router = useRouter();
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
        router.push('/dashboard');
        router.refresh();
      }
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'totp_required') {
        setStep('totp');
        setError('Enter the 6-digit code from your authenticator.');
      } else {
        setError(caught instanceof ApiError ? caught.message : 'Login failed');
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
      router.push('/dashboard');
      router.refresh();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Invalid code');
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
            <p className="font-mono text-2xs text-muted">restricted access</p>
          </div>
        </div>

        {step === 'password' ? (
          <form onSubmit={submitPassword} className="space-y-3">
            <div>
              <Label htmlFor="email">Email</Label>
              <Input id="email" type="email" autoComplete="username" required value={email} onChange={(event) => setEmail(event.target.value)} />
            </div>
            <div>
              <Label htmlFor="password">Password</Label>
              <Input id="password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} />
            </div>
            {error ? <p className="rounded border border-bad/40 bg-bad/10 p-2 font-mono text-2xs text-bad">{error}</p> : null}
            <Button className="w-full" size="lg" loading={busy}>Continue</Button>
          </form>
        ) : null}

        {step === 'totp' ? (
          <form onSubmit={submitPassword} className="space-y-3">
            <p className="text-xs text-muted">Two-factor is mandatory. Code for <span className="font-mono">{email}</span></p>
            <div>
              <Label htmlFor="code">TOTP</Label>
              <Input id="code" inputMode="numeric" autoComplete="one-time-code" maxLength={8} required autoFocus value={code} onChange={(event) => setCode(event.target.value)} />
            </div>
            {error ? <p className="rounded border border-bad/40 bg-bad/10 p-2 text-2xs text-bad">{error}</p> : null}
            <Button className="w-full" size="lg" loading={busy}>Verify</Button>
          </form>
        ) : null}

        {step === 'setup' ? (
          <form onSubmit={enableTotp} className="space-y-3">
            <p className="text-xs text-muted">First login: scan and confirm a code to enable TOTP.</p>
            {qr ? <img src={qr} alt="TOTP QR" className="mx-auto rounded border border-line" /> : null}
            <p className="break-all rounded border border-line bg-bg p-2 font-mono text-2xs">{secret}</p>
            <div>
              <Label htmlFor="setup-code">Code</Label>
              <Input id="setup-code" inputMode="numeric" maxLength={8} required autoFocus value={code} onChange={(event) => setCode(event.target.value)} />
            </div>
            {error ? <p className="rounded border border-bad/40 bg-bad/10 p-2 text-2xs text-bad">{error}</p> : null}
            <Button className="w-full" size="lg" loading={busy}>Enable and sign in</Button>
          </form>
        ) : null}

        <p className="mt-5 text-center text-2xs text-muted">argon2id · TOTP · SameSite=Strict</p>
      </div>
    </main>
  );
}
