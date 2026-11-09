import { prisma } from '../server/lib/db';
import { hmac, openSecret, unb64u, victimCanonical, b64u } from '../server/lib/crypto';

async function main(): Promise<void> {
  const id = process.argv[2]!;
  const victim = await prisma.victim.findUnique({ where: { id } });
  if (!victim) {
    console.log('no victim');
    return;
  }
  const token = openSecret(victim.deviceTokenEnc);
  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = b64u(Buffer.from(new Uint8Array(16).map(() => Math.floor(Math.random() * 256))));
  const path = '/api/v1/ws-ticket';
  const body = Buffer.alloc(0);
  const sig = hmac(unb64u(token), victimCanonical('POST', path, ts, nonce, body)).toString('base64url');
  const res = await fetch('http://127.0.0.1:4000' + path, {
    method: 'POST',
    headers: { 'x-victim-id': id, 'x-timestamp': ts, 'x-nonce': nonce, 'x-signature': sig }
  });
  console.log(res.status, await res.text());
}

main()
  .catch((error) => console.error(error))
  .finally(() => void prisma.$disconnect());
