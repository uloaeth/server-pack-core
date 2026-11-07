# Security checklist

Run this before exposing the panel, and again every quarter.

## Transport and headers

- [ ] Caddy issues and renews TLS; HSTS is `max-age=31536000; includeSubDomains; preload`.
- [ ] Only 22/80/443 are open; Postgres and Redis publish no host ports.
- [ ] `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, Referrer-Policy set.
- [ ] CSP has no `unsafe-eval`; `connect-src` limited to `'self'`, `ws:`, `wss:`.
- [ ] `TRUST_PROXY=1` only behind Caddy; direct API access is firewalled.
- [ ] API and web bind to the internal network in production.

## Authentication

- [ ] Passwords are argon2id (64 MiB, t=3, p=1); policy is 12+ chars with mixed classes.
- [ ] TOTP is mandatory on first login and cannot be skipped by direct API call.
- [ ] Session JWT is `httpOnly`, `Secure`, `SameSite=Strict`, 12 h maximum, server track.
- [ ] Five failures lock the account; login is rate limited per IP.
- [ ] `IP_ALLOWLIST` is set for an admin-only deployment; VPN/CIDR documented.
- [ ] Viewer accounts cannot enqueue tasks, cancel, retag, or delete (verified by test).
- [ ] Admin accounts use distinct mailboxes and TOTP devices.

## Secrets

- [ ] `.env` and `secrets/*` are `0600`, owned by the deploy user, out of git.
- [ ] `JWT_SECRET` differs per environment and is rotated after any leak.
- [ ] `MASTER_KEY` is backed up offline; restore is tested.
- [ ] Build secrets live only in DB and in the jar config; a lost build is revoked, not reused.
- [ ] `SEED_*` output is captured and the seed terminal history is cleared.
- [ ] Device tokens are sealed at rest and never appear in logs.

## Agent transport

- [ ] HMAC covers method, path, timestamp, nonce and body hash; nonces expire in 15 min.
- [ ] Clock skew window is 300 s and enforced both ways.
- [ ] Enroll tokens are hashed at rest and revoked with the build.
- [ ] Artifact chunks are hash-checked and offsets are monotonic; re-commit is idempotent.
- [ ] `build_secret` never crosses the wire after enrollment (only the derived tag).
- [ ] Revoked victim gets `410` and stops storing.

## Data and panel

- [ ] Every mutation writes `audit_log` in the same transaction as the effect.
- [ ] Audit export is admin/operator only and itself audited.
- [ ] Artifact paths are normalized; `..` cannot escape the artifact root.
- [ ] Upload size and chunk count are bounded; expiry is 24 h.
- [ ] SQL is parameterized through Prisma; log redaction covers cookies and auth headers.
- [ ] WebSocket upgrade revalidates the session, not just the cookie name.

## Backups and recovery

- [ ] Nightly encrypted `pg_dump` and volume archive, 14 daily / 8 weekly.
- [ ] Backups restored to a scratch host quarterly; row counts compared.
- [ ] Restore runbook names the key custodian and the rebuild order (db → api → worker).
- [ ] Disk alerts at 70 %; artifact retention per profile documented.

## Monitoring

- [ ] Healthcheck on `/api/console/health`; container restarts on three failures.
- [ ] Failed logins, 5xx, upload failures and revoked builds reach the notification bell.
- [ ] Caddy access log rotated at 20 MB, 10 files.
- [ ] `fail2ban` protects SSH; Docker daemon bound to localhost only.

## Known accepted risks

- A client-side build secret is extractable from a running jar; it protects at-rest
  artifacts and enroll, not the agent itself.
- DPAPI material and wallet passwords live in the victim session; an administrator
  on the victim can always reach them.
- Redis is trusted inside the compose network; it holds nonces and pub/sub only.
- Device revocation is soft: a copied `config.json` can re-enroll under a new
  hostname until the build itself is revoked.
