ALTER TABLE "Artifact" ALTER COLUMN "uploadId" DROP NOT NULL;
ALTER TABLE "Artifact" ADD COLUMN "source" TEXT NOT NULL DEFAULT 'api';
ALTER TABLE "Artifact" ADD COLUMN "discordMessageId" TEXT;
CREATE UNIQUE INDEX "Artifact_discordMessageId_key" ON "Artifact"("discordMessageId");

ALTER TABLE "Task" ADD COLUMN "discordMessageId" TEXT;

CREATE TABLE "DiscordLink" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "channelId" TEXT NOT NULL,
  "guildId" TEXT NOT NULL,
  "victimId" UUID NOT NULL,
  "lastMessageId" TEXT NOT NULL DEFAULT '0',
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "DiscordLink_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "DiscordLink_channelId_key" ON "DiscordLink"("channelId");
CREATE UNIQUE INDEX "DiscordLink_victimId_key" ON "DiscordLink"("victimId");
CREATE INDEX "DiscordLink_guildId_idx" ON "DiscordLink"("guildId");
ALTER TABLE "DiscordLink" ADD CONSTRAINT "DiscordLink_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "DiscordPart" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "channelId" TEXT NOT NULL,
  "base" TEXT NOT NULL,
  "index" INTEGER NOT NULL,
  "path" TEXT NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "DiscordPart_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "DiscordPart_channelId_base_index_key" ON "DiscordPart"("channelId", "base", "index");
CREATE INDEX "DiscordPart_channelId_base_idx" ON "DiscordPart"("channelId", "base");
