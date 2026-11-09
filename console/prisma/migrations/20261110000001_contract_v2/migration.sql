-- Contract v2 negotiation state. Existing rows stay on contract 1 because the
-- negotiated value is written only when a 0.4 agent declares X-Contract: 2.
ALTER TABLE "Victim" ADD COLUMN "contractVersion" INTEGER NOT NULL DEFAULT 1;
ALTER TABLE "Victim" ADD COLUMN "capabilities" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];
ALTER TABLE "Victim" ADD COLUMN "configEpoch" INTEGER NOT NULL DEFAULT 1;
