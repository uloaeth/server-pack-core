# Changelog

## 0.1.4

- Signed agent updates: apply, restart and automatic rollback on startup failure.
- Signed mod staging with the swap deferred until the game exits.
- Manual `update rollback` for both artifacts.
- Shared update gate so manual and automatic runs never overlap.
- Opt-in automatic updates: first slot after 10 minutes, then every 24 h ±10 % jitter, backoff 1 h → 2 h → 6 h → 24 h, slots persisted across restarts.
- Rollback marker so a failed startup counts as a failure with backoff.
- The Windows update helper is still untested on a real host.

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
