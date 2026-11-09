-- Identity state, token graces and per-epoch salt history.
ALTER TABLE "Build" ADD COLUMN "enrollGraceHours" INTEGER NOT NULL DEFAULT 24;

ALTER TABLE "Victim" ADD COLUMN "keyEpoch" INTEGER NOT NULL DEFAULT 1;
ALTER TABLE "Victim" ADD COLUMN "identityState" TEXT NOT NULL DEFAULT 'confirmed';
ALTER TABLE "Victim" ADD COLUMN "graceUntil" TIMESTAMP(3);
ALTER TABLE "Victim" ADD COLUMN "prevTokenEnc" TEXT;
ALTER TABLE "Victim" ADD COLUMN "prevTokenExpiresAt" TIMESTAMP(3);
ALTER TABLE "Victim" ADD COLUMN "pendingTokenEnc" TEXT;
ALTER TABLE "Victim" ADD COLUMN "pendingTokenExpiresAt" TIMESTAMP(3);

CREATE TABLE "VictimKeyEpoch" (
    "id" UUID NOT NULL,
    "victimId" UUID NOT NULL,
    "epoch" INTEGER NOT NULL,
    "saltEnc" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "retiredAt" TIMESTAMP(3),
    CONSTRAINT "VictimKeyEpoch_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "VictimKeyEpoch_victimId_epoch_key" ON "VictimKeyEpoch"("victimId", "epoch");
CREATE INDEX "VictimKeyEpoch_victimId_epoch_idx" ON "VictimKeyEpoch"("victimId", "epoch");

ALTER TABLE "VictimKeyEpoch" ADD CONSTRAINT "VictimKeyEpoch_victimId_fkey"
    FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Existing scheme-2 devices already have their epoch-1 salt.
INSERT INTO "VictimKeyEpoch" ("id", "victimId", "epoch", "saltEnc")
SELECT gen_random_uuid(), "id", 1, "keySaltEnc"
FROM "Victim"
WHERE "keyScheme" = 2 AND "keySaltEnc" IS NOT NULL;
