import { PrismaClient } from '@prisma/client';
const p = new PrismaClient();
const a = await p.artifact.findUnique({ where: { id: process.argv[2] } });
console.log(a?.status, JSON.stringify(a?.manifest));
process.exit(0);
