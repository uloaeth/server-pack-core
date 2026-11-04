# Changelog

## 0.1.3

- Self-update infrastructure: signed `update.json` manifest, RSA-3072 public key embedded in the agent, release workflow with dry run.
- Discord credentials moved out of the tracked tree into generated `DiscordSecrets`, sourced from `local.properties` or `SPC_*` environment variables.
- `checkSecrets` and `release` Gradle tasks; releases fail fast on placeholders.
- Full Spanish to English translation of commands, messages and documentation.
- Gateway WebSocket transport with per-connection callbacks and REST fallback.
- Gateway hardening: 4014 intent downgrade, resume, backoff with jitter, 429 honoring `retry_after`.
- JUnit suite for the agent (24 tests) and bytecode verification for the mod and agent jars.

## 0.1.2

- Baseline before the release tooling work.
