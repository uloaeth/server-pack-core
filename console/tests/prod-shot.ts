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
  for (const [path, out] of [['/logs?level=error','/tmp/opencode/prod-logs.png'], ['/vault','/tmp/opencode/prod-vault.png'], ['/events','/tmp/opencode/prod-events.png']] as const) {
    await page.goto(`https://c2.spc-labpanel.com${path}`, { waitUntil: 'networkidle' }); await page.waitForTimeout(700); await page.screenshot({ path: out });
  }
  await browser.close();
}
main().catch((e) => { console.error(e); process.exit(1); });
