import type { Prisma, PrismaClient } from '@prisma/client';

export type AuditInput = {
  operatorId?: string | null;
  action: string;
  entity: string;
  entityId?: string | null;
  ip?: string | null;
  data?: Record<string, unknown>;
};

type Db = PrismaClient | Prisma.TransactionClient;

export async function audit(db: Db, input: AuditInput): Promise<void> {
  await db.auditLog.create({
    data: {
      operatorId: input.operatorId ?? null,
      action: input.action,
      entity: input.entity,
      entityId: input.entityId ?? null,
      ip: input.ip ?? null,
      data: (input.data ?? {}) as Prisma.InputJsonValue
    }
  });
}
