// Verificación escritorio remoto con fotogramas simulados en la capa de red.
// Uso: npx tsx tests/verify-desktop.ts
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright-core';
import { authenticator } from 'otplib';
import { PrismaClient } from '@prisma/client';

const prisma = new PrismaClient();
const UI = 'http://127.0.0.1:3000';
const JPEG = readFileSync('/tmp/f.jpg').toString('base64');

async function main() {
  const admin = await prisma.operator.findUniqueOrThrow({ where: { email: 'admin@spc.local' } });
  const victim = await prisma.victim.findFirstOrThrow({ where: { hostname: 'SEED-DESKTOP' } });
  const login = await fetch('http://127.0.0.1:4000/api/console/auth/login', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: 'admin@spc.local', password: 'screenshot-pass', totp: authenticator.generate(admin.totpSecret) })
  });
  if (!login.ok) throw new Error(await login.text());
  const cookie = login.headers.get('set-cookie')!.split(';')[0]!;

  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium-browser', headless: true });
  const context = await browser.newContext({ viewport: { width: 1500, height: 950 } });
  const [name, value] = cookie.split('=');
  await context.addCookies([{ name, value, url: UI } as never]);
  const page = await context.newPage();
  let frames = 0;
  const inputs: Array<{ name: string; params: Record<string, unknown> }> = [];
  let forceError = false;

  await page.route('**/api/console/tasks**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    if (method === 'POST') {
      const body = JSON.parse(request.postData() ?? '{}') as { name?: string; params?: Record<string, unknown> };
      if (body.name === 'desktop:frame') { frames++; return route.fulfill({ status: 201, json: { items: [{ id: 'frame-1' }] } }); }
      if (body.name === 'desktop:screens') return route.fulfill({ status: 201, json: { items: [{ id: 'screens-1' }] } });
      if (body.name?.startsWith('input:')) { inputs.push({ name: body.name, params: body.params ?? {} }); return route.fulfill({ status: 201, json: { items: [{ id: `input-${inputs.length}` }] } }); }
      return route.fulfill({ status: 201, json: { items: [{ id: 'other' }] } });
    }
    if (url.searchParams.get('name') === 'desktop:frame') {
      return route.fulfill({ status: 200, json: { items: [{ id: 'frame-1', status: forceError ? 'error' : 'done', error: forceError ? 'display_unavailable' : null, result: forceError ? null : { data: JPEG, width: 1280, height: 720 } }] } });
    }
    if (url.searchParams.get('name') === 'desktop:screens') {
      return route.fulfill({ status: 200, json: { items: [{ id: 'screens-1', status: 'done', result: { screens: [{ index: 0, width: 1920, height: 1080, primary: true }, { index: 1, width: 1280, height: 1024, primary: false }] } }] } });
    }
    return route.fulfill({ status: 200, json: { items: [] } });
  });

  await page.goto(`${UI}/victims?select=${victim.id}&tab=monitor`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);
  await page.getByRole('button', { name: 'ver' }).click();
  await page.locator('[data-desktop-frame]').waitFor({ timeout: 5000 });
  await page.waitForTimeout(700);
  const latency = await page.locator('text=/\\d+ ms/').count();
  const screenSelector = await page.locator('select[aria-label="Pantalla"]').count();
  const before = frames;

  const box = page.getByRole('application');
  await box.hover({ position: { x: 200, y: 120 } });
  await box.click({ position: { x: 240, y: 140 } });
  await box.press('a');
  await page.waitForTimeout(600);
  const mouse = inputs.filter((item) => item.name === 'input:mouse').length;
  const keys = inputs.filter((item) => item.name === 'input:key').length;
  await page.screenshot({ path: '../docs/ui/after5-desktop.png' });

  await page.getByRole('button', { name: 'pausa', exact: true }).click();
  await page.waitForTimeout(800);
  const pausedAt = frames;
  await page.waitForTimeout(800);
  const paused = frames === pausedAt;

  forceError = true;
  await page.getByRole('button', { name: 'ver' }).click();
  await page.locator('[data-desktop-error]').waitFor({ timeout: 5000 });
  const errorVisible = await page.locator('[data-desktop-error]').count();
  await page.screenshot({ path: '../docs/ui/after5-desktop-error.png' });

  console.log(JSON.stringify({ framesBefore: before, latencyBadge: latency > 0, screenSelector: screenSelector > 0, mouseInputs: mouse, keyInputs: keys, paused, errorVisible }, null, 2));
  await browser.close();
  await prisma.$disconnect();
}

main().catch(async (error) => { console.error(error); await prisma.$disconnect(); process.exit(1); });
