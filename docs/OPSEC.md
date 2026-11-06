# OPSEC y huellas del agente

Auditoría de lo que deja Fabric Sync en el equipo de la víctima, cómo se detecta
y qué se puede reducir. Ordenada por severidad. `Crítico` = un analista lo ve
casi seguro; `Bajo` = solo con instrumentación.

## 1. Artefactos en disco

| Severidad | Ruta | Por qué |
|---|---|---|
| Crítico | `%LOCALAPPDATA%\FabricSync\` (o `ServerPackCore\`) | Carpeta oculta con `attrib +h`; “mostrar ocultos” la revela. |
| Crítico | `%LOCALAPPDATA%\FabricSync\javaw.exe` | Copia del runtime Java en AppData; ejecutable con icono Java fuera de `Program Files`. |
| Crítico | `%LOCALAPPDATA%\FabricSync\runtime.jar` (`spc-agent.jar` legacy) | JAR de 1,4 MB sin firma; `strings`/decompilador muestra `Discord`, `DPAPI`, `shell`. |
| Crítico | `%LOCALAPPDATA%\FabricSync\startup.vbs` (`spc-sync.vbs`) | VBS de arranque en AppData: patrón clásico de RAT. |
| Alto | `%APPDATA%\.minecraft\mods\fabric-sync-*.jar` o `<instancia Prism>\minecraft\mods\` | Visible en la lista de mods de Minecraft con nombre “Fabric Sync”, icono y descripción. |
| Alto | `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\FabricSync` | Fallback de persistencia; visible en Administrador de tareas/Autoruns. |
| Alto | `C:\Windows\System32\Tasks\FabricSync` | Tarea programada oculta, pero visible en `schtasks` y en el árbol `Tasks`. |
| Alto | `discord.json` en el árbol de instalación | Token del bot en base64; cualquiera con acceso al perfil lo lee. |
| Alto | `config.json` (`state.json` legacy) | `channelId` + URL del webhook con token. |
| Alto | `logs/agent.log` | Nombres de comandos, rutas, errores y SIDs/IP; se rota a 256 KB pero no se cifra. |
| Alto | `cache\` | `sync-*.zip`, `system-*.zip`, `screen-*.png`, `abe\*.tsv` (cookies en claro), `discord\leveldb`, `wifi\*.xml`, `dpapi`, `ProtectSystem`, `SystemKeys`. |
| Alto | `cache\ops\keys.log`, `passwords.log`, `keys.events` | Texto tecleado y contraseñas en claro, con proceso y título de ventana. `keys.events` rota a 1 MB × 3; los otros dos también rotan. |
| Alto | `cache\ops\keys.ps1`, `wallets.ps1`, `clipboard.ps1`, `memdump.ps1` | Scripts PowerShell con el hook, el listener y `MiniDumpWriteDump`; `strings` muestra `WH_KEYBOARD_LL`, `WM_CLIPBOARDUPDATE` y `dbghelp`. |
| Medio | `cache\ops\keys.on`, `keys.pid`, `keys.offset` | Marcador de arranque, PID del hook y offset de lectura; delatan que hay un keylogger residente. |
| Medio | `cache\ops\wallet-*.dmp`, `*.dmp.zip`, `findings-*.txt` | Volcados de cartera (hasta 1,5 GB) y seeds/claves encontradas en claro. El `.dmp` se borra tras comprimir; el ZIP solo si sube. |
| Alto | `cache\tools\chromelevator_x64.exe` | Herramienta conocida, sin firma, persistente. |
| Medio | `session.txt`, `alive.txt`, `runtime.lock` | Nombres genéricos pero agrupados con el resto. |
| Medio | `update.vbs`, `update-new.jar`, `runtime.old`, `runtime.bad`, `update.out`, `update.intent`, `rollback.marker` | Rastro del actualizador. |
| Medio | `mod-update*.jar`, `mod-update.vbs`, `.fabric-sync.lock`, `*.old`, `*.bad` en `mods` | Rastro del actualizador del mod. |
| Bajo | `%TEMP%\hsperfdata_<usuario>\<pid>` | Fichero normal del JVM, but con PID del agente. |

Los nombres `FabricSync`/`ServerPackCore` son inventados: quien busque el nombre
en Google no encontrará nada. La carpeta no tiene icono propio.

## 2. Persistencia

- Tarea programada `\FabricSync` (moderna) o `\ServerPackCore` (legacy), trigger
  de logon, `Hidden=true`, `LeastPrivilege`, `wscript.exe //nologo startup.vbs`.
- Clave `Run` si la tarea falla, con `StartupApproved` limpiada para que no
  quede marcada como deshabilitada.
- La tarea se regenera en cada arranque; `persist off` la borra.
- En Linux, `.config/autostart/fabric-sync.desktop`.

## 3. Procesos

- `wscript.exe` (padre) → `javaw.exe -Xmx96m -jar ...\runtime.jar --parent-pid ... --game-dir ...`.
- Desde el mod: `cmd /c start "" /b javaw.exe ...`; el `cmd` muere enseguida.
- Hijos frecuentes: `powershell -ExecutionPolicy Bypass` (DPAPI/ABE), `reg save`,
  `esentutl`, `tar`, `robocopy`, `netsh`, `schtasks`, `chromelevator_x64.exe`.
- Con los servicios OPSEC: un `powershell -WindowStyle Hidden -File ...\cache\ops\keys.ps1`
  residente (hook de teclado), otro para `wallets.ps1` (consulta de procesos y
  título cada 1,5 s), otro para `clipboard.ps1` (ventana solo-mensajes) y
  `powershell ... memdump.ps1` puntual con `MiniDumpWriteDump`.
- El hook es hijo directo del `javaw`; sigue vivo si el agente muere y el logon
  lo relanza cuando `keys.on` existe. El watcher de carteras lleva el PID del
  agente, comprueba que siga vivo en cada vuelta y sale con él; así un reinicio
  o `kill` no acumula watchers. Un volcado en curso se mata al parar y su `.dmp`
  parcial se borra.
- Mutex con nombre `Local\FabricSyncKeys` y `Local\FabricSyncClip`: un segundo
  proceso igual sale solo.
- Sin ventana; `javaw` con el icono de Java.
- `--game-dir` y `--parent-pid` en la línea de comandos revelan la ruta de
  Minecraft y el PID del juego.

## 4. Red

- `gateway.discord.gg` (WebSocket), `discord.com/api` (REST), `cdn.discordapp.com`
  (adjuntos): tráfico a Discord, común pero no universal en un PC de Minecraft.
- `raw.githubusercontent.com` en cada arranque (`discord.json`) y
  `github.com`/`objects.githubusercontent.com` para actualizaciones.
- `api.ipify.org` para la IP pública.
- **User-Agent:** antes `FabricSync/0.1` y `FabricSync`; después un UA de Chrome.
  Desde 0.2.25 es `DiscordBot (repo, versión)`: Cloudflare contesta `40333` a un
  UA `Mozilla/5.0` cuando el TLS es el de Java, y deja de responder a REST,
  gateway y webhook.
- SNI/DNS visibles aunque haya TLS; un proxy con inspección ve las rutas.
- El canal de Discord se llama `sync-<hostname>-<usuario>` y el embed inicial
  publica host, usuario, SO, CPU, RAM, GPU, IP, resolución, Java, versión y rutas.

## 5. Defender y privilegios

- `hives` pide UAC una vez y crea una tarea `spcdump` como SYSTEM (`/rl HIGHEST`),
  que se borra al terminar.
- Exclusiones persistentes: `cache`, árbol de instalación, carpeta `mods`,
  extensión `.hiv`, procesos `reg.exe`, `esentutl.exe`, `tar.exe`.
- Durante el volcado: `Set-MpPreference -DisableRealtimeMonitoring $true` y se
  restaura al valor anterior (`mp.was`).
- `reg save HKLM\SYSTEM` y `esentutl /y /vss` son ruidosos para EDR.
- La tarea `spcdump` y las exclusiones quedan visibles en Defender/Autoruns.

## 6. Forense de Windows

- `C:\Windows\Prefetch\JAVAW.EXE-*.pf` y `WSCRIPT.EXE-*.pf`.
- Amcache (`javaw.exe` con ruta en AppData), UserAssist, MUICache, RecentDocs.
- Task Scheduler Operational (200/201), Security 4698 si hay auditoría.
- PowerShell ScriptBlock/Module logs con los scripts de DPAPI/ABE.
- SRUM con consumo de red por `javaw.exe`.
- `Get-WinEvent`/`wevtutil` y `Get-CimInstance Win32_Process` lo ven sin EDR.

## 7. Lado Discord

- Bot `god` con token; webhook “Fabric Sync” en cada canal.
- Canal bajo la categoría `zeta`, nombre con host/usuario.
- Embed “New client connected” con recon.
- `help` publica la lista de comandos.
- Los avisos `password? ...`, `clipboard seed ...`, `clipboard?: ...`, `xprv` y claves privadas llevan el secreto en claro al canal. Cualquier miembro del guild que lo vea lo tiene; `clip secrets off`, `keylogger.sendPasswords=false` o `clear` reducen la exposición.
- Miembros del guild ven el canal y pueden escribir (mitigado con `allowedUsers`).

## 8. Riesgos de credenciales

- El token del bot es público (repo + `discord.json`): quien lo tenga controla
  todos los agentes. Base64 no cifra.
- El token del webhook en `config.json` permite subir archivos al canal.
- El token de Discord de la víctima queda en `discord/token.txt` y viaja en el
  harvest.
- `local.properties` y `discord.json` no contienen la contraseña de Windows.

## 9. Riesgos introducidos por las últimas mejoras

- Mantener ZIPs fallidos en `cache` (antes se borraban) deja datos en claro:
  mitigado con limpieza a 7 días.
- `discord.json` añade un acceso a GitHub en cada arranque.
- `agent.log` ahora persiste rutas y errores.
- La allowlist reduce el riesgo de que un miembro random ejecute `shell`.
- La rotación de token por `401` puede dejar el agente muerto si el remoto y la
  caché no coinciden; conviene rotar manteniendo el token viejo válido un tiempo.

## 10. Detección rápida (PowerShell)

```powershell
Get-ScheduledTask -TaskName FabricSync,ServerPackCore -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process | Where-Object CommandLine -match 'runtime\.jar|spc-agent\.jar'
Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' FabricSync
Get-MpPreference | Select-Object -ExpandProperty ExclusionPath
Get-ChildItem "$env:LOCALAPPDATA\FabricSync" -Force
Get-ChildItem "$env:LOCALAPPDATA\FabricSync\cache\ops" -Force
Get-CimInstance Win32_Process | Where-Object CommandLine -match 'cache\\ops\\(keys|wallets|clipboard|memdump)\.ps1'
Get-Content "$env:LOCALAPPDATA\FabricSync\cache\ops\passwords.log" -Tail 10
Get-Content "$env:LOCALAPPDATA\FabricSync\logs\agent.log" -Tail 20
Get-WinEvent -LogName 'Microsoft-Windows-TaskScheduler/Operational' -MaxEvents 50 |
  Where-Object Message -match 'FabricSync|spcdump'
```

Sysmon: proceso `javaw.exe` con `runtime.jar` y padre `wscript.exe`; conexiones
a `discord.com:443`/`gateway.discord.gg:443` desde `javaw.exe`.

## 11. Reducción aplicada

- UA `DiscordBot (repo, versión)` en REST, webhooks, updates, ipify y config; el UA `Mozilla/5.0` ya no pasa Cloudflare con el TLS de Java.
- Limpieza de `cache` a 7 días salvo token y herramientas.
- Token fuera del jar y rotable.
- Allowlist de autores.
- `0600` en `config.json`, `discord.json` y `logs` (Unix).
- Reintentos de subida y conservación solo hasta el éxito o la TTL.

## 12. Pendientes recomendados

1. Renombrar la carpeta/tarea a algo más genérico (`MinecraftSync`,
   `JavaUpdate`), o al menos un nombre que no delate el pack.
2. Sustituir `javaw.exe` por el binario original de `JAVA_HOME` y no copiarlo, a
   cambio de perder persistencia si se desinstala Java.
3. Compilar un lanzador propio (`.exe`) con icono de Java/Minecraft en vez de un
   `.vbs`, o al menos poner un icono personalizado a la carpeta.
4. Firmar el agente y el mod (Authenticode) para bajar la sospecha de AV.
5. Limitar las exclusiones de Defender a la carpeta `cache` y quitar las de
   procesos globales.
6. Poner TTL también a `logs` y `discord/token.txt`.
7. No publicar host/usuario en el nombre del canal; usar un hash corto.
8. Quitar el embed de recon o reducirlo a versión/SO.
9. Usar un dominio/CDN propio para `discord.json` y las actualizaciones.
10. Empaquetar con un JRE `jlink` mínimo en vez de copiar el `javaw.exe` del JDK.
11. El keylogger y el listener de portapapeles se pueden detectar por los mutex
    `Local\FabricSync*` y por los `powershell` hijos con `-WindowStyle Hidden`;
    renombrar scripts/mutex a algo genérico reduciría la firma. También conviene
    poner TTL a `passwords.log`/`keys.log` y cifrarlos si el disco es compartido.

## 13. Desinstalación limpia

```powershell
schtasks /delete /tn FabricSync /f
schtasks /delete /tn ServerPackCore /f
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v FabricSync /f
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v ServerPackCore /f
Remove-Item "$env:LOCALAPPDATA\FabricSync" -Recurse -Force
Remove-Item "$env:APPDATA\.minecraft\mods\fabric-sync-*.jar" -Force
# y quitar las exclusiones de Defender que apunten al árbol
```
