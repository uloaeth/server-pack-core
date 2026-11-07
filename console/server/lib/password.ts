import argon2 from 'argon2';
import { env } from './env';

export const hashPassword = (plain: string): Promise<string> =>
  argon2.hash(plain, {
    type: argon2.argon2id,
    memoryCost: env.ARGON2_MEMORY_KIB,
    timeCost: env.ARGON2_TIME,
    parallelism: 1
  });

export async function verifyPassword(hash: string, plain: string): Promise<boolean> {
  try {
    return await argon2.verify(hash, plain);
  } catch {
    return false;
  }
}

/** 12+ chars, upper, lower and a digit. */
export function passwordPolicy(password: string): string | null {
  if (password.length < 12) return 'password must be at least 12 characters';
  if (!/[a-z]/.test(password)) return 'password needs a lowercase letter';
  if (!/[A-Z]/.test(password)) return 'password needs an uppercase letter';
  if (!/[0-9]/.test(password)) return 'password needs a digit';
  return null;
}
