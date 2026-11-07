ALTER TABLE "Notification" ADD COLUMN "dedupeKey" TEXT;
CREATE INDEX "Notification_dedupeKey_createdAt_idx" ON "Notification"("dedupeKey", "createdAt");

ALTER TABLE "DiscordLink" ADD COLUMN "missingAt" TIMESTAMP(3);

CREATE TABLE "DiscordIgnore" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "channelId" TEXT NOT NULL,
  "reason" TEXT NOT NULL DEFAULT '',
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "DiscordIgnore_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "DiscordIgnore_channelId_key" ON "DiscordIgnore"("channelId");
CREATE INDEX "DiscordIgnore_createdAt_idx" ON "DiscordIgnore"("createdAt");
