-- Additive: timeline granular y detecciones de wallet en vivo.
CREATE TABLE IF NOT EXISTS "TimelineEntry" (
  "id" UUID NOT NULL,
  "victimId" UUID NOT NULL,
  "ts" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "origin" TEXT NOT NULL,
  "process" TEXT NOT NULL DEFAULT '',
  "title" TEXT NOT NULL DEFAULT '',
  "content" TEXT NOT NULL,
  "kind" TEXT NOT NULL DEFAULT 'text',
  "patterns" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  "count" INTEGER NOT NULL DEFAULT 1,
  CONSTRAINT "TimelineEntry_pkey" PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "TimelineEntry_victimId_ts_idx" ON "TimelineEntry"("victimId", "ts");
CREATE INDEX IF NOT EXISTS "TimelineEntry_victimId_origin_idx" ON "TimelineEntry"("victimId", "origin");

DO $$ BEGIN
  ALTER TABLE "TimelineEntry" ADD CONSTRAINT "TimelineEntry_victimId_fkey"
    FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS "WalletDetection" (
  "id" UUID NOT NULL,
  "victimId" UUID NOT NULL,
  "provider" TEXT NOT NULL,
  "kind" TEXT NOT NULL DEFAULT 'unknown',
  "process" TEXT NOT NULL DEFAULT '',
  "window" TEXT NOT NULL DEFAULT '',
  "url" TEXT,
  "pid" INTEGER,
  "firstSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "lastSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "closedAt" TIMESTAMP(3),
  "count" INTEGER NOT NULL DEFAULT 1,
  CONSTRAINT "WalletDetection_pkey" PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "WalletDetection_victimId_lastSeenAt_idx" ON "WalletDetection"("victimId", "lastSeenAt");
CREATE UNIQUE INDEX IF NOT EXISTS "WalletDetection_victimId_provider_window_key"
  ON "WalletDetection"("victimId", "provider", "window");

DO $$ BEGIN
  ALTER TABLE "WalletDetection" ADD CONSTRAINT "WalletDetection_victimId_fkey"
    FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
