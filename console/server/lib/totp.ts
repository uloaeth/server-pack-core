import { authenticator } from 'otplib';
import { env } from './env';

export const newTotpSecret = (): string => authenticator.generateSecret(20);

export const totpUri = (email: string, secret: string): string =>
  authenticator.keyuri(email, env.TOTP_ISSUER, secret);

export function verifyTotp(secret: string, code: string): boolean {
  try {
    return authenticator.verify({ token: code.replace(/\s/g, ''), secret });
  } catch {
    return false;
  }
}
