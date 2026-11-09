# Informe técnico: funcionamiento del RAT

**Fecha:** 9 de noviembre de 2026  
**Ámbito:** rama `fix/0.3.7-rat-hardening` de `uloaeth/server-pack-core`  
**Método:** revisión del código del agente, del contrato v1, del panel SPC Console y del despliegue real en VPS. Sin pruebas destructivas sobre las víctimas.

---

## 1. Resumen ejecutivo

El proyecto implementa un RAT en dos artefactos separados:

1. Un **mod de Minecraft** aparentemente inocuo (`Fabric Sync`) que actúa como lanzador y stager.
2. Un **agente Java independiente** que se instala en el perfil del usuario, sobrevive al cierre del juego y habla con un **panel C2 propio** por HTTPS/WebSocket, con Discord como transporte de respaldo.

El agente recoge reconocimiento del sistema, credenciales de navegador, carteras de criptomonedas, portapapeles, teclas y seeds; permite shell remota, gestión de ficheros, capturas, streaming de pantalla, webcam, micrófono, cifrado de carpetas y DDoS. El panel añade multioperador, roles, TOTP, builds por encargo, artefactos cifrados y auditoría.

El diseño prioriza la **persistencia y la recolección pasiva** sobre la interacción: no necesita administrador para instalarse, no abre puertos en la víctima y se camufla como una actualización de Java.

---

## 2. Alcance y metodología

Se han revisado:

- `agent/src/main/java/com/serverpackcore/agent/**` — stager, agente, transporte, tareas.
- `agent/src/main/java/com/serverpackcore/agent/crypto/**` — módulo de recolección y cifrado.
- `src/main/java/com/serverpackcore/**` — mod y mixin.
- `contract/v1/**` — contrato HTTP y WebSocket congelado.
- `console/**` — API Fastify, panel Next.js, Prisma, Caddy y trabajos.
- `docs/**` — rutas, cobertura, errores conocidos y OPSEC.
- Estado real del VPS, contenedores, base de datos y tareas recientes.

No se ha modificado el contrato ni se han ejecutado acciones destructivas durante la auditoría.

---

## 3. Arquitectura general

```
Minecraft (Windows/Linux/macOS)
  └─ mod Fabric Sync (stager)  ──extrae y lanza──▶  agente Java independiente
                                                       │
                          HTTPS firmado + WebSocket ───┤
                                                       ├──▶  Caddy (443) ──▶ API Fastify ──▶ PostgreSQL
                                                       │                          │
                          Discord (respaldo) ──────────┘                          └──▶ artefactos cifrados
                                                                                       │
Operador (escritorio/móvil) ──HTTPS/TOTP──▶ Panel Next.js ─────────────────────────────┘
```

- **Mod:** solo contiene el mixin, el stager y el `agent.bin`. No tiene lógica C2.
- **Agente:** proceso Java puro, sin dependencias de Minecraft. Shell, recolección y transporte.
- **Panel:** Next.js para la interfaz, Fastify para la API, Prisma/PostgreSQL, Redis para nonces y pub/sub, Caddy para TLS.
- **Transporte primario:** API HTTP firmada con HMAC + canal WebSocket de control.
- **Transporte de respaldo:** Discord (gateway/REST), desactivable con `DISCORD_BRIDGE`.

El servidor nunca inicia conexiones hacia la víctima.

---

## 4. Componentes

### 4.1 Mod de Minecraft y stager

- Mod cliente para **Minecraft 1.20.1**, Fabric Loader 0.20.1 y Fabric API 0.92.12+1.20.1.
- `id` interno `serverpackcore`; nombre visible `Fabric Sync`; icono y descripción de sincronizador de configuración.
- El mixin en `MinecraftClient.tick` espera a estar dentro de un mundo entre **20 y 60 segundos** y dispara **una vez por sesión**.
- Extrae el agente embebido (`serverpackcore/agent.bin`) al directorio de instalación, lo lanza con el runtime Java real y aplica ocultación.
- Conserva el agente si el juego se cierra. Un `runtime.lock` y un `.version` evitan duplicados y fuerzan reinstalación si faltan ficheros.
- La versión del mod y la del agente van sincronizadas por el build.

### 4.2 Agente

- Java 17, sin dependencias externas, `-Xmx256m` en perfil `lite` y `-Xmx512m` en `full`, limitado a 2 CPUs.
- Hilos separados para heartbeat, polling y pool de comandos; un fallo de módulo no tumba el transporte.
- Watchdog de 90 s que recrea los clientes si quedan colgados; sale con código 21 para que el stager lo relance.
- Autenticación por token de dispositivo y firma HMAC por petición; nonces, sello temporal y control de secuencia.
- Actualización firmada con RSA-3072, rollback y watchdog posterior.
- Persistencia autorreparable por tarea programada oculta o `Run`, con lanzador `.vbs`.

### 4.3 Panel SPC Console

- Interfaz Next.js 14 con vistas de equipos, resumen, tareas, builds, eventos, logs, auditoría, operadores y ajustes.
- API Fastify con validación Zod, control de rol, rate limit, auditoría por mutación y TOTP obligatorio.
- Autenticación argon2id, sesiones JWT `httpOnly`/`Secure`/`SameSite=Strict` con registro y revocación.
- Artefactos cifrados en reposo, procesado asíncrono y retención programada.
- Geometría de red: Postgres y Redis solo en la red interna de Docker.

### 4.4 Transporte y contrato

**HTTP firmado**

- `POST /enroll` con token de build; el resto de rutas exigen `X-Victim-Id`, `X-Timestamp`, `X-Nonce` y `X-Signature`.
- Firma: `HMAC-SHA256(device_token, METHOD + path + timestamp + nonce + sha256(body))`.
- Ventana de reloj ±300 s, nonces de 15 minutos, detección de replay.
- Límites: cuerpo JSON 1 MiB, resultado de tarea 4 MiB, artefacto 512 MiB.
- Errores tipificados: `401`, `403 revoked`, `409 replay`, `429`, `410`, `413`.

**WebSocket de control**

- Ticket de un solo uso, TTL 30 s, máximo tres tickets por dispositivo.
- Claves HKDF por dirección derivadas del ticket y del nonce de handshake.
- Secuencia persistente por dirección, ventana de deduplicación de 1024, nonces por dirección.
- HMAC por frame, ACK explícito, ping/pong, límite de 4 comandos en vuelo y 262 144 bytes por frame.
- Los artefactos **nunca** viajan por WebSocket; solo control y resultados pequeños.

**Discord (respaldo)**

- Un canal `sync-<host>-<usuario>` por víctima, webhook propio y adjuntos por partes.
- Sin puerto entrante; todo son peticiones salientes.
- Menos seguro: el token del bot es público y los artefactos van sin cifrar a Discord.
- En este despliegue está desactivado (`DISCORD_BRIDGE=0`).

---

## 5. Instalación, camuflaje y persistencia

### Rutas

| Sistema | Directorio |
|---|---|
| Windows | `%LOCALAPPDATA%\Microsoft\Java` (oculto y de sistema) |
| Linux | `$XDG_DATA_HOME/FabricSync` o `~/.local/share/FabricSync` |
| macOS | `~/Library/Application Support/FabricSync` |

Los árboles antiguos `FabricSync` y `ServerPackCore` se migran al arrancar.

### Camuflaje

- Carpeta con atributos oculto y sistema; contenido oculto recursivamente.
- Nombres genéricos: `Microsoft\Java`, `Java Runtime Update`, `runtime.jar`, `startup.vbs`.
- Copia local de `javaw.exe` con icono de Java.
- `wscript.exe` como padre y `javaw.exe` sin ventana.
- Sin servicios ni drivers; todo en espacio de usuario.

### Persistencia

- Tarea programada de logon `\Java Runtime Update`, oculta y con privilegio mínimo.
- Respaldo con valor `Run` si `schtasks` falla.
- Se regenera en cada arranque; `persist off` la elimina.
- En Linux, `.desktop` en `~/.config/autostart`.

### Actualización

- Manifiesto firmado; clave privada fuera del repositorio, pública embebida.
- Descarga, verificación, parada, intercambio y reinicio desacoplado.
- Rollback automático si el nuevo agente no supera los 20 s o muere en los 10 minutos siguientes.
- Autoactualización opcional cada 24 h con jitter; manual por defecto.

---

## 6. Enrolamiento y autenticación

1. El jar lleva `build_id`, `build_secret`, URL del panel y perfil.
2. El token de enrolamiento se deriva de `build_secret` y se guarda hasheado en el servidor.
3. `POST /enroll` es idempotente por `(build_id, hostname, username)`; rota el token de dispositivo.
4. El servidor devuelve `victim_id`, `device_token` y parámetros de heartbeat/polling.
5. Cada petición posterior va firmada; el token de dispositivo se sella con AES-256-GCM y `MASTER_KEY` en reposo.
6. Un build o una víctima revocados cortan el acceso (`403`/`410`).

La identidad no depende de la IP ni del nombre del canal.

---

## 7. Catálogo de tareas

Todas las tareas se validan dos veces: en el panel contra el contrato y en el agente antes de tocar disco. Las pesadas se ejecutan con cancelación y presupuesto.

| Tarea | Transporte | Función | Plataforma |
|---|---|---|---|
| `shell` | HTTP | Ejecuta `cmd /c` o `/bin/sh -c`, une stdout/stderr, corta a 60 s; salidas largas como artefacto | Todas |
| `file_pull` | HTTP | Empaqueta un fichero o árbol con límite `max_mb` | Todas |
| `files` | HTTP | `list`, `upload` desde URL, `mkdir`, `rename`, `delete`, `compress` | Todas |
| `screenshot` | HTTP | Captura de pantalla como PNG | Todas |
| `monitor` | HTTP | Ráfaga de fotogramas JPEG en Base64, hasta 30 s y 30 frames | Con entorno gráfico |
| `webcam` | HTTP | Foto de webcam vía `ffmpeg`/DirectShow/V4L2/AVFoundation | Con webcam y `ffmpeg` |
| `mic` | HTTP | Clip de audio de 1–10 s | Con micrófono y `ffmpeg` |
| `scan_wallets` | HTTP | Inventario de extensiones, carteras de escritorio y ficheros de seed | Windows completo; copia en otros |
| `dump_vault` | HTTP | Copia el almacén de una cartera y descifra si hay contraseña conocida | Windows/Electron |
| `crack_vault` | HTTP | Diccionario acotado por tiempo y CPU; al acertar encadena el dump | Windows/Electrum |
| `clip_start` / `clip_stop` | HTTP | Arranca o para el secuestro de direcciones y avisos de secretos | Windows |
| `harvest` | HTTP | ZIP de reconocimiento, navegadores, carteras, Discord, WiFi, claves de producto | Windows; parcial en otros |
| `agent` | HTTP | Subcomandos: `update`, `persist`, `sleep`, `reconnect`, `kill`, `ls`, `hives`, `bootstrap`, `clip…`, `wallet…`, `memdump` | Según subcomando |
| `cancel` | WS/HTTP | Cancela una tarea por `task_id` | Todas |
| `ransomware` | HTTP | Cifra, descifra o consulta estado con AES-256-GCM | Todas |
| `ddos` | HTTP | HTTP o TCP concurrente contra host/puerto durante N segundos | Todas |

Acciones del panel que no son tareas: abrir detalle, etiquetas, chat, revocar, eliminar, abrir URL, message box, consultar registro, buscar ficheros y descarga masiva (esta última encola varios `file_pull`).

---

## 8. Módulos de captura

### 8.1 Wallets

- Inventario de extensiones Chromium (MetaMask, Phantom, Rabby, Trust, Coinbase, Keplr, Solflare, TronLink, etc.) y carteras de escritorio (Exodus, Electrum, Ledger Live, Atomic, Coinomi, Geth, Parity, Bitcoin Core, Monero).
- Copia LevelDB/SQLite a temporal antes de leer para evitar el `LOCK`.
- Descifrado v3 (PBKDF2-SHA256 + AES-GCM) con contraseña o diccionario.
- NSS de Firefox, DPAPI y claves app-bound de Chrome con sus fallbacks.
- Cobertura y límites documentados en `docs/COVERAGE.md`.

### 8.2 Keylogger

- Hook `WH_KEYBOARD_LL` en PowerShell oculto con mutex propio.
- Contexto de proceso y título; `IsPassword` por UI Automation.
- Flush por Enter, Tab, cambio de foco o 10 s de inactividad.
- Solo bloques de 6–256 caracteres en ventanas de credenciales llegan a `passwords.log`.
- Detecta seeds BIP39 escritas, con checksum, una sola vez.
- Resuelve el layout activo con `ToUnicodeEx` (AltGr, teclas muertas).

### 8.3 Portapapeles

- Ventana solo-mensajes con `WM_CLIPBOARDUPDATE`, sin polling.
- Sustituye direcciones BTC/ETH/SOL/LTC/DOGE/BCH/TRX/XRP/ADA por las del operador, con validación estricta de checksum y rotación por moneda.
- Solo toca texto de una línea de hasta 128 caracteres; no rompe URLs ni multilínea.
- Avisa además de seeds, `xprv`, claves privadas y contraseñas copiadas desde gestores o carteras.

### 8.4 Harvest

- ZIP con cuentas de launcher, pistas de autologon, token de Discord, carteras, `Local State`, cookies, logins, historial, DPAPI, `options.txt`, `servers.dat` y lista de mods.
- En Windows ejecuta ChromElevator por navegador para cookies y contraseñas app-bound (TSV).
- Variantes por `kind`: `browsers`, `discord`, `telegram`, `wifi`, `productkey` o `all`.
- Corta a 12 MB, omite ficheros de más de 2 MB y sube por partes.
- WiFi y ProductKey son solo Windows.

### 8.5 Credenciales y DPAPI

- Descifra cookies y logins de Chromium con DPAPI y claves app-bound; Firefox con NSS si no hay contraseña maestra.
- Exporta material DPAPI, hives y claves de sistema cuando se solicita `hives` con UAC.
- Añade exclusiones de Defender y desactiva la monitorización en tiempo real durante el volcado.
- Todo el material queda en artefactos cifrados; el análisis offline se hace con `decrypt.py` y `ingest-case.sh`.

---

## 9. Artefactos y cifrado

- Contenedor deterministico: `manifest.json`, su SHA-256 y blobs individuales.
- Cada blob: Deflate y después AES-256-GCM con nonce por chunk y AAD ligado al manifiesto.
- Clave derivada de `build_secret`, `build_id`, `victim_id` y un prefijo de nonce; el servidor puede descifrar y el `build_secret` no viaja tras el enrolamiento.
- Subida por chunks de 512 KiB con hash por chunk, reanudación por offset y commit idempotente.
- Caducidad de subidas a 24 h; el procesado extrae y clasifica wallets, capturas, keylogs y seeds.
- Límites por perfil: `lite` 64 MiB, `full` 512 MiB.

---

## 10. Panel de operación

- **Equipos:** tabla filtrable, selección múltiple, acciones por contexto, detalle en drawer amplio con pestañas de resumen, ficheros, keylogger, portapapeles, credenciales, wallets, tareas, eventos, monitor y chat.
- **Resumen:** mapa, sistemas, países y actividad en vivo.
- **Tareas:** cola con leases, timeouts y cancelación.
- **Builds:** creación por perfil y módulos, compilación con inyección de config y descarga.
- **Eventos, logs y auditoría:** telemetría, salida del API y una fila por mutación.
- **Seeds:** hallazgos BIP-39, `xprv`, hex y WIF.
- **Operadores y ajustes:** roles, TOTP, sesiones, contraseña, versiones e IP allowlist.

Roles: `viewer` lee y exporta; `operator` encola y cancela; `admin` además edita builds, víctimas y operadores. Toda mutación se audita en la misma transacción.

---

## 11. Builds e inyección de configuración

- El JAR base es universal y apunta a producción; el panel inyecta `serverpackcore/config.json` por build.
- La config lleva `build_id`, `build_secret`, URL del C2, perfil, módulos y límites.
- El `build_id` deriva la clave de artefactos; el token de enrolamiento se guarda hasheado.
- Perfil `lite`: sin seeds ni credenciales y artefactos de 64 MiB.
- Revocar un build invalida todos sus agentes; eliminar exige que no queden víctimas o reasignarlas.
- El JAR compilado se sirve solo a operadores autenticados.

---

## 12. Modelo de datos y retención

Tablas principales: operadores, sesiones, builds, víctimas, tareas, subidas, artefactos, wallets, portapapeles, keylogs, capturas, eventos, notificaciones, enlaces e ignorados de Discord, partes, nonces WS y auditoría.

- Tokens de dispositivo cifrados; tokens de build hasheados.
- Estado online por heartbeat de 90 s, nunca por WebSocket.
- Retención: keylogs 30 días, portapapeles 14, eventos no-semilla 90, notificaciones leídas 30, artefactos fallidos 7. Las seeds se conservan hasta borrar la víctima.
- Borrar una víctima elimina en cascada su material y marca el canal de Discord como ignorado.

---

## 13. OPSEC y límites

**Huellas principales**

- Carpeta y lanzador en AppData, tarea `Java Runtime Update`, valor `Run`.
- `runtime.jar` sin firma y scripts PowerShell en `cache\ops`.
- `keys.log`, `passwords.log` y `seeds.log` en claro.
- Tráfico a Discord, GitHub y `api.ipify.org`; UA de bot.
- Prefetch, Amcache, UserAssist, SRUM y eventos de tarea programada.

**Límites duros**

- Servicios OPSEC y la mayoría del descifrado de navegador son solo Windows.
- El agente Linux conserva los comandos pero responde `Windows only` cuando aplica.
- Sin sesión interactiva no hay hook de teclado ni portapapeles.
- Las carteras bloqueadas no se abren sin contraseña; el volcado de memoria es la vía independiente.
- Chrome app-bound exige contexto DPAPI del usuario; si no, se guarda material para análisis offline.
- Firefox con contraseña maestra y Exodus/Phantom cifrados requieren trabajo offline.
- Defender puede marcar ChromElevator como `HackTool`.

---

## 14. Seguridad del despliegue

- TLS de Let's Encrypt y HSTS con `preload`.
- Solo 22/80/443 expuestos; Postgres y Redis sin puertos de host.
- `ufw`, `fail2ban` con jail SSH y actualizaciones automáticas.
- Secretos `0600`, fuera de git; `MASTER_KEY` sella tokens de dispositivo.
- Contraseñas argon2id, bloqueo tras cinco fallos, rate limit de login.
- TOTP obligatorio; sesión de 12 h con revocación.
- Cabeceras `DENY`, `nosniff`, Referrer-Policy y CSP sin `unsafe-eval`.
- Auditoría de cada mutación y validación de rutas de artefactos.
- Backups diarios de PostgreSQL y prueba de restauración.

Riesgos aceptados: el `build_secret` es extraíble de un jar en ejecución; un administrador de la víctima puede llegar a DPAPI y contraseñas; la revocación de dispositivo es blanda si se copia la config.

---

## 15. Estado actual del despliegue

- VPS Debian 13, `c2.spc-labpanel.com`, TLS válido, health 200 y stack completo sano.
- Panel en la rama `fix/0.3.7-rat-hardening`, con el rediseño de interfaz y las correcciones de compatibilidad.
- JAR base recompilado desde el código 0.3.7 y verificado; build `lab-c2` compilado y descargable.
- Víctima de prueba `dell` (Windows 11) registrada; en la última comprobación seguía con el agente antiguo 0.3.8 y offline, pendiente de relanzar con el JAR nuevo.
- Backups manuales correctos y restauración probada.
- CI en verde en Java 21 y 25.

---

## 16. Limitaciones y errores conocidos

- `agent.bin` obsoleto si no se ejecuta el agente completo antes de empaquetar; corregido en el build.
- El build local 0.3.8 es anterior en funciones a 0.3.7 y se trata como tal.
- `harvest kind` se degrada a harvest completo en agentes viejos.
- `webcam`/`mic` requieren 0.3.7 y `ffmpeg`.
- Artefactos de un agente con config desajustada fallan con `Unsupported state or unable to authenticate data`.
- `wmic` puede faltar en Windows 11 reciente.
- `screenshot` falla en sesiones sin display.
- AltGr y teclas muertas dependen del layout.
- Chrome 127+ sin sesión desde el login no se descifra.

El catálogo completo está en `docs/KNOWN_ERRORS.md`.

---

## 17. Checklist operativa

1. Verificar DNS y TLS antes de arrancar Caddy.
2. Confirmar health 200 y login con TOTP.
3. Comprobar que la víctima reporta la versión de agente esperada.
4. Probar `whoami`, `tasklist`, captura y un `harvest` pequeño.
5. Revisar artefactos descifrados y errores de tarea.
6. Cambiar la contraseña inicial y guardar TOTP.
7. Copiar backups fuera del VPS y probar restauración.
8. Revocar builds y víctimas al cerrar un grupo.

---

## 18. Glosario

- **Build:** configuración y token que identifican un jar y su clave de artefactos.
- **Víctima:** equipo enrolado con un build.
- **Stager:** parte del mod que extrae y lanza el agente.
- **Artefacto:** contenedor cifrado con material recogido.
- **Lease:** alquiler temporal de una tarea por el agente.
- **Nonce:** valor de un solo uso contra replay.
- **TOTP:** segundo factor temporal del operador.
- **DPAPI:** protección de datos de Windows ligada a máquina/usuario.
- **ABE:** cifrado app-bound de Chromium.
- **BIP-39:** estándar de frases mnemónicas con checksum.
