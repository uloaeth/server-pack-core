-- Build secrets move to envelope encryption; scheme 1 keeps the legacy token
-- derivation so already-installed jars keep enrolling.
ALTER TABLE "Build" ADD COLUMN "enrollScheme" INTEGER NOT NULL DEFAULT 1;
ALTER TABLE "Victim" ADD COLUMN "keyScheme" INTEGER NOT NULL DEFAULT 1;
ALTER TABLE "Victim" ADD COLUMN "keySaltEnc" TEXT;
