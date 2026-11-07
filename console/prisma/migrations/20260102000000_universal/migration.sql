ALTER TABLE "Build" ADD COLUMN "universal" BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX "Build_universal_idx" ON "Build"("universal");
