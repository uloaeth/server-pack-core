import { chromium } from 'playwright-core';
import { authenticator } from 'otplib';
import { PrismaClient } from '@prisma/client';

async function main() {
  const prisma = new PrismaClient();
  const admin = await prisma.operator.findUniqueOrThrow({ where: { email: 'admin@spc.local' } });
  const victim = await prisma.victim.findFirstOrThrow({ where: { hostname: 'SEED-DESKTOP' } });
  await prisma.$disconnect();
  const login = await fetch('http://127.0.0.1:4000/api/console/auth/login', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: 'admin@spc.local', password: 'screenshot-pass', totp: authenticator.generate(admin.totpSecret) })
  });
  const cookie = login.headers.get('set-cookie')!.split(';')[0]!;
  const [name, value] = cookie.split('=');
  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium-browser', headless: true });
  const context = await browser.newContext({ viewport: { width: 1500, height: 950 } });
  await context.addCookies([{ name: name!, value: value!, url: 'http://127.0.0.1:3000' }]);
  const page = await context.newPage();
  await page.goto(`http://127.0.0.1:3000/victims?select=${victim.id}&tab=boveda`, { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: /phantom/i }).click();
  await page.waitForTimeout(400);
  await page.locator('section .divide-y button').first().click();
  await page.waitForTimeout(600);
  await page.getByRole('button', { name: /^ver$/ }).first().click();
  await page.waitForTimeout(500);
  await page.screenshot({ path: '../docs/ui/after-vault-seed.png' });
  await browser.close();
}
main().catch((error) => { console.error(error); process.exit(1); });
