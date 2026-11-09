import { prisma } from '../server/lib/db';
import { hmac, openSecret, unb64u, victimCanonical, b64u } from '../server/lib/crypto';
async function main() {
  const id = '89917a5b-c428-4e30-9b61-ec8804ab2608';
  const v = await prisma.victim.findUnique({ where: { id } });
  const token = openSecret(v!.deviceTokenEnc);
  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = b64u(Buffer.from(new Uint8Array(16).map(() => Math.floor(Math.random() * 256))));
  const sig = hmac(unb64u(token), victimCanonical('POST', '/api/v1/ws-ticket', ts, nonce, Buffer.alloc(0))).toString('base64url');
  const head = { 'x-victim-id': id, 'x-timestamp': ts, 'x-nonce': nonce, 'x-signature': sig };
  for (let i = 0; i < 2; i++) {
    const r = await fetch('http://127.0.0.1:4000/api/v1/ws-ticket', { method: 'POST', headers: head });
    console.log(i === 0 ? 'fresh' : 'replay', r.status, (await r.text()).slice(0, 60));
  }
}
main().finally(() => void prisma.$disconnect());
