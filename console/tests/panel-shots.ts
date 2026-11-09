// Capturas locales de /victims con el seed. Requiere API :4000, web :3000 y un agente falso.
import { chromium } from 'playwright-core';
import { authenticator } from 'otplib';
import { PrismaClient } from '@prisma/client';

async function main() {
  const prisma = new PrismaClient();
  const admin = await prisma.operator.findUniqueOrThrow({ where: { email: 'admin@spc.local' } });
  const victim = await prisma.victim.findFirstOrThrow({ where: { hostname: 'SEED-DESKTOP' } });
  await prisma.task.deleteMany({ where: { victimId: victim.id, status: 'running' } });
  await prisma.task.create({ data: { victimId: victim.id, name: 'monitor', params: { seconds: 5, fps: 1, quality: 60 }, status: 'running', timeoutSec: 90, leaseUntil: new Date(Date.now() + 60_000) } });
  await prisma.$disconnect();
  const response = await fetch('http://127.0.0.1:4000/api/console/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email: 'admin@spc.local', password: 'screenshot-pass', totp: authenticator.generate(admin.totpSecret) }) });
  if (!response.ok) throw new Error(await response.text());
  const cookie = response.headers.get('set-cookie')!.split(';')[0]!;
  const [name, value] = cookie.split('=');
  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium-browser', headless: true });
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await context.addCookies([{ name: name!, value: value!, url: 'http://127.0.0.1:3000' }]);
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:3000/victims?q=zzz', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  await page.screenshot({ path: '../docs/ui/victims-vacio.png' });
  await page.goto(`http://127.0.0.1:3000/victims?select=${victim.id}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: '../docs/ui/victims-seleccion.png' });
  await page.locator(`[data-victim="${victim.id}"]`).click({ button: 'right' });
  await page.waitForTimeout(500);
  await page.screenshot({ path: '../docs/ui/victims-menu.png' });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
  await page.screenshot({ path: '../docs/ui/victims-tarea.png' });
  await browser.close();
}
main().catch((error) => { console.error(error); process.exit(1); });
