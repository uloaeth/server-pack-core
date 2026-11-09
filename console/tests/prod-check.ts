import { chromium } from 'playwright-core';
import { authenticator } from 'otplib';
async function main() {
  const totp = 'OQ4X4P2NAVQT4SYKIBGA6PTUAQ5SEFQ5';
  const login = await fetch('https://c2.spc-labpanel.com/api/console/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email: 'admin@spc.local', password: 'a4PAoPUc49t/w/V0yPi6s6jopjqgAAfD', totp: authenticator.generate(totp) }) });
  if (!login.ok) throw new Error(await login.text());
  const cookie = login.headers.get('set-cookie')!.split(';')[0]!; const [name, value] = cookie.split('=');
  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium-browser', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1500, height: 950 } });
  await ctx.addCookies([{ name: name!, value: value!, url: 'https://c2.spc-labpanel.com', secure: true, httpOnly: true }]);
  const page = await ctx.newPage();
  await page.goto('https://c2.spc-labpanel.com/logs?level=error', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  console.log('select', await page.locator('select[aria-label="Nivel mínimo"]').inputValue());
  console.log('rows', await page.locator('[role="log"] > div').count());
  console.log('info rows', await page.locator('[role="log"] >> text=INFO').count());
  await page.screenshot({ path: '/tmp/opencode/prod-logs-filtered.png' });
  await browser.close();
}
main().catch((e) => { console.error(e); process.exit(1); });
