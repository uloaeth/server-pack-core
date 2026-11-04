# Changelog

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
