-- Persisted control-channel state: cursors and the per-direction dedup window.
ALTER TABLE "Victim" ADD COLUMN "wsSeqIn" BIGINT NOT NULL DEFAULT 0;
ALTER TABLE "Victim" ADD COLUMN "wsSeqOut" BIGINT NOT NULL DEFAULT 0;

CREATE TABLE "WsSeen" (
    "id" BIGSERIAL NOT NULL,
    "victimId" UUID NOT NULL,
    "direction" VARCHAR(3) NOT NULL,
    "frameId" UUID NOT NULL,
    "seq" BIGINT NOT NULL,
    "at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "WsSeen_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "WsSeen_victimId_direction_frameId_key" ON "WsSeen"("victimId", "direction", "frameId");
CREATE INDEX "WsSeen_victimId_direction_id_idx" ON "WsSeen"("victimId", "direction", "id");

ALTER TABLE "WsSeen" ADD CONSTRAINT "WsSeen_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;
