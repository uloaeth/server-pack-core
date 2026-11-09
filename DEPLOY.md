# Deploy and release

## Repository secrets

Two independent sets, both required by `.github/workflows/release.yml`:

| Secret | Contents |
| --- | --- |
| `SPC_BOT_TOKEN` | Discord bot token burned into the agent |
| `SPC_GUILD_ID` | Guild ID |
| `SPC_CATEGORY_ID` | Category ID |
| `SPC_SIGNING_KEY_B64` | `base64 -w0 ~/.config/spc/update-signing-key.pem` |
| `SPC_AGENT_P12_B64` | `base64 -w0 agent-signing.p12` (PKCS#12 estable, `docs/SIGNING.md`) |
| `SPC_AGENT_P12_PASS` | contraseña del PKCS#12 |
| `SPC_AGENT_ALIAS` | alias de la clave de firma |
| `SPC_TSA_URL` | TSA RFC3161 primaria |
| `SPC_TSA_FALLBACK` | TSA de reintento |

El release reutiliza `SPC_AGENT_P12*` como `SPC_PE_P12*` para Authenticode del
PE; no hay un keystore separado.

Set them without echoing:

```sh
gh secret set SPC_BOT_TOKEN --repo uloaeth/server-pack-core < token.txt
gh secret set SPC_GUILD_ID --repo uloaeth/server-pack-core < guild.txt
gh secret set SPC_CATEGORY_ID --repo uloaeth/server-pack-core < category.txt
base64 -w0 ~/.config/spc/update-signing-key.pem | gh secret set SPC_SIGNING_KEY_B64 --repo uloaeth/server-pack-core
```

Fork pull requests have no secrets; `build.yml` writes no `local.properties` there, the build falls back to `SPC_PLACEHOLDER` and still compiles.

## Signing key

RSA-3072, generated once and kept offline:

```sh
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out ~/.config/spc/update-signing-key.pem
chmod 600 ~/.config/spc/update-signing-key.pem
openssl pkey -in ~/.config/spc/update-signing-key.pem -pubout \
  -out agent/src/main/resources/serverpackcore/update-public.pem
```

The public fingerprint in the README must match the key commit; rotate both together and never commit the private key.

## Migración 0.4

Una base ya migrada con el nombre antiguo debe marcarse antes de desplegar:

```sh
npx prisma migrate resolve --applied 20261109000000_secret_separation
npx prisma migrate deploy
npx tsx scripts/reseal.ts   # debe terminar con pending=0
```

Una base nueva ejecuta todo desde cero. El panel arranca en `degraded` si queda
alguna fila sin sellar.

## Release

1. Bump `version.txt` (único origen) y etiqueta el commit con `v` + esa versión. El mod y `BuildInfo` leen el mismo fichero.
2. Add the version section to `CHANGELOG.md`.
3. Merge to `main` and tag `v<version>`.
4. The tag runs `release.yml`: builds on JDK 21 and 25 with `check`, runs `./gradlew release`, asserts the three versions, stages `agent-<version>.jar`, `fabric-sync-<version>.jar`, `SHA256.txt`, `update.json` and `update.json.sig`, verifies the signature and only then creates the GitHub release.

`latest/download/update.json` resolves because the release is the latest non-prerelease one. Do not mark it prerelease.

Dry run before tagging: dispatch `release.yml` with `dry_run` enabled. It uploads the same assets as a workflow artifact and creates no release.

## VBS helpers

`tools/vbs-check.sh` regenerates both Windows helpers for a modern and a legacy tree and runs them under `wine cscript`; VBScript compilation errors fail the run. Published releases require the same check because 0.2.1-0.2.3 shipped helpers that `wscript` rejected.

## Embedded ABE

`agent/src/main/resources/serverpackcore/chromelevator_x64.exe` is pinned to
ChromElevator v0.20.0 (MIT). `AbeTest` fails if its SHA-256 differs from
`e857298fd2f8d1c7d48780769433f33e7b3ceaae5ea5a74c13ce8c10bcc7b690`.
Update `NOTICE` and that constant together when bumping the tool.

## Version identity

The mod version, the agent version and the tag are one identity. `release.yml` fails when they disagree, so a stale `version.txt` cannot produce a release with a misleading manifest.
