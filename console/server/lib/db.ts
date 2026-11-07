import { PrismaClient } from '@prisma/client';

declare global {
  // eslint-disable-next-line no-var
  var __spcPrisma: PrismaClient | undefined;
}

export const prisma =
  global.__spcPrisma ??
  new PrismaClient({
    log: process.env.PRISMA_LOG === '1' ? ['query', 'warn', 'error'] : ['warn', 'error']
  });

if (process.env.NODE_ENV !== 'production') global.__spcPrisma = prisma;
