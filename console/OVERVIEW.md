# SPC Console

Self-hosted command console for the Fabric Sync agent. Contract v1 lives in
[`contract/v1/CONTRACT.md`](../contract/v1/CONTRACT.md); the agent module is in
[`agent/src/main/java/com/serverpackcore/agent/crypto`](../agent/src/main/java/com/serverpackcore/agent/crypto).

- `console/` — Next.js 14 panel + Fastify API + Prisma/PostgreSQL + Caddy TLS.
- Deploy from zero: [`console/README.md`](README.md).
- Security checklist: [`console/SECURITY.md`](SECURITY.md).
- Coverage: [`docs/COVERAGE.md`](../docs/COVERAGE.md) · paths: [`docs/PATHS.md`](../docs/PATHS.md) · known errors: [`docs/KNOWN_ERRORS.md`](../docs/KNOWN_ERRORS.md).

Quick local stack:

```bash
cd console
cp .env.example .env            # fill DATABASE_URL, JWT_SECRET, MASTER_KEY, PUBLIC_URL
docker compose up -d db redis
docker compose run --rm -e ROLE=seed -e SEED=1 api
docker compose up
```

Local without Docker:

```bash
cd console
npm ci
npx prisma migrate dev
npm run seed
npm run dev                     # web :3000, api :4000
```

The panel derives online state from the last heartbeat (90 s window), never from
the WebSocket. A viewer can read everything and export; only operator/admin
mutate, and every mutation lands in `audit_log`.
