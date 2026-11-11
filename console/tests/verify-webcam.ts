// Verificación webcam: snapshot, stream, stop y error visible.
// Uso: npx tsx tests/verify-webcam.ts
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

  const made: string[] = [];
  const make = async (name: string, status: string, result: Record<string, unknown>, error: string | null = null) => {
    const task = await prisma.task.create({ data: { victimId: victim.id, name, status: status as never, params: {}, result: result as never, error, finishedAt: new Date() } });
    made.push(task.id);
    return task.id;
  };
  await make('webcam:devices', 'done', { video: ['Integrated Camera', 'USB Cam'], audio: [] });
  await make('webcam:snapshot', 'done', { data: JPEG, bytes: 344, device: 'Integrated Camera' });
  await make('webcam:stream', 'done', { frames: [JPEG, JPEG, JPEG], fps: 10, count: 3, device: 'Integrated Camera' });

  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium-browser', headless: true });
  const context = await browser.newContext({ viewport: { width: 1500, height: 950 } });
  const [name, value] = cookie.split('=');
  await context.addCookies([{ name: name!, value: value!, url: UI }]);
  const page = await context.newPage();
  await page.goto(`${UI}/victims?select=${victim.id}&tab=monitor`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);

  const snapshot = await page.locator('[data-webcam-shot]').count();
  const options = await page.locator('select[aria-label="Cámara"] option').count();
  await page.screenshot({ path: '../docs/ui/after3-webcam-snapshot.png' });

  await page.getByRole('button', { name: /stream \d+ fps/ }).click();
  await page.waitForTimeout(500);
  const streamTask = await prisma.task.findFirst({ where: { victimId: victim.id, name: 'webcam:stream' }, orderBy: { createdAt: 'desc' } });
  await prisma.task.update({ where: { id: streamTask!.id }, data: { status: 'done', result: { frames: [JPEG, JPEG, JPEG], fps: 10, count: 3, device: 'Integrated Camera' } as never, finishedAt: new Date() } });
  await page.waitForTimeout(2500);
  const streaming = await page.locator('[data-webcam-stream]').count();
  await page.getByRole('button', { name: 'parar' }).click();
  const stopped = await page.locator('text=detenido (cámara liberada)').count();
  await page.screenshot({ path: '../docs/ui/after3-webcam-stream.png' });

  await make('webcam:snapshot', 'error', {}, 'webcam_unavailable: Could not find video device with name [Integrated Camera]');
  await page.waitForTimeout(2500);
  const errorVisible = await page.locator('[data-webcam-error]').count();
  await page.screenshot({ path: '../docs/ui/after3-webcam-error.png' });

  console.log(JSON.stringify({ snapshot, deviceOptions: options, streaming, stopped, errorVisible }, null, 2));

  await prisma.task.deleteMany({ where: { id: { in: made } } });
  await browser.close();
  await prisma.$disconnect();
}

main().catch(async (error) => { console.error(error); await prisma.$disconnect(); process.exit(1); });
