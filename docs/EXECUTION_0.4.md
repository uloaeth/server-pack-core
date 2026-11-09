# Ejecución 0.4.0 — correcciones de F0, puerta Windows y contrato v2

**Orden aplicado:** B → A → C → D → E1–E3. Este documento es el registro de A y
de las decisiones. B vive en `docs/REENROLL.md`; la matriz C en
`docs/WINDOWS_GATE.md`; D en `docs/CONTRACT_V2.md`; E1 en `docs/SIGNING.md`,
E2 en `docs/PINNING.md` y E3 en `docs/OBFUSCATION.md`.

## Decisiones escritas

1. **Salt de identidad.** El salt se crea una vez por víctima y no cambia al
   enrolar. La rotación es explícita (`POST /victims/:id/rotate-key`), auditada
   y conserva tres epochs.
2. **Gracia 24 h.** Un enrolamiento sin prueba nunca invalida el token vigente;
   el nuevo queda `pending`. `Build.enrollGraceHours=0` desactiva.
3. **`key_probe` verificador.** `HMAC(artifact_key_epoch, "probe")`, por epoch,
   nunca material reversible.
4. **`panic` conserva.** `dry_run=1` no muta; el 410 retiene la cola en
   `outbox/held`; `restore` solo reabre enrolamiento.
5. **La gracia no confirma por sí sola.** Solo `active`/`prev` confirman; el
   `pending` firmando no expulsa al agente real.

---

## A1. Salt como identidad y claves desacopladas

**Motivo:** P0.4 se rompía justo en el caso para el que existe: re-enrolar
reemitía el salt y dejaba la cola indescifrable. P1.3 heredaría el fallo.

**Diseño:** `Victim.keyEpoch` + `VictimKeyEpoch` (máx. 3). `artifact_key`,
`outbox_key` y `log_key` derivan del PRK con labels distintos y epoch. El
outbox lleva `SPCB2 || flags || epoch || nonce || GCM` y `-e<epoch>-` en el
nombre; historial local de 3 salts. Epoch evadido → `outbox/dead/unknown_epoch`.

**Áreas:** `crypto.ts`, `agent.ts`, `artifact.ts`, `services/victim.ts`,
`Outbox.java`, `CryptoConfig.java`, `ArtifactPack.java`, `schema.prisma`,
migración `20261110000000_reenroll_epochs`.

**Riesgo:** complejidad de epochs, acotada a 3; colas huérfanas a `dead`.

**Aceptación:** re-enrolar conserva salt y drena la cola; rotación deja cerrar
la época anterior; las tres claves difieren. Tests: 16 de cripto/estado y 3 de
outbox.

**Estado:** hecho. **Esfuerzo:** 2,5 d.

## A2. Re-enrolamiento endurecido

**Motivo:** el token de build es determinista; con host/usuario se rotaba el
token real y se recibía el salt.

**Diseño:** camino 1 con `device_token` válido (identidad confirmada, mismo
salt, rotación con `prev` 24 h); camino 2 sin prueba (`pending`, `active`
intacto, `unverified_enroll`, confirmación al volver el real). Documentado en
`docs/SECRETS.md` y `docs/REENROLL.md`.

**Áreas:** `routes/agent.ts`, `services/victim.ts`, `plugins/victim-auth.ts`,
`CryptoModule.java`, `CryptoTransport.java`, `docs/SECRETS.md`.

**Riesgo:** la gracia como superficie; 24 h, auditada, desactivable.

**Aceptación:** dos re-enrolamientos no dejan sin token; un tercero no rompe al
real; el salt no cambia; el evento se registra; un `confirmed` que corre a la vez
que un `unverified` conserva el token rotado (el camino de gracia no reescribe
`deviceTokenEnc`). Tests puros de decisión, promoción y autenticación con tres
tokens, más la carrera en `/enroll`.

**Estado:** hecho. **Esfuerzo:** 2,5 d.

## A3. `ATOMIC_MOVE` entre volúmenes

**Diseño:** temporal dentro de `outbox/`; `AtomicMoveNotSupportedException` →
`fsync` + rename + `flags=1`; barrido de `tmp-*` al arrancar. Forzable con
`spc.forceNonAtomic`/`SPC_FORCE_NON_ATOMIC`.

**Áreas:** `Outbox.java`, `WindowsGate`, `WINDOWS_GATE.md`.

**Aceptación:** perfil en otro volumen funciona; un corte deja la entrada
íntegra o recuperable por barrido; renombrar por reintento no invalida el AAD
(el contador se normaliza y el test lo cubre).

**Estado:** hecho; escenarios 1 y 2 del gate. **Esfuerzo:** 1 d.

## A4. Digest canónico

**Diseño:** excluye solo `META-INF/*.SF|DSA|RSA|EC` y las líneas `*-Digest:` del
manifiesto. `MANIFEST.MF` (incluido `Main-Class`/`Launcher-Agent-Class`) y
`META-INF/services` quedan cubiertos. Mismo algoritmo en Gradle y Stager.

**Áreas:** `build.gradle`, `agent/build.gradle`, `Stager.java`,
`AgentDigestTest`, `WindowsGate`.

**Aceptación:** alterar manifiesto o services rompe; dos firmados dan el mismo
hash.

**Estado:** hecho; 2 tests + escenario 6. **Esfuerzo:** 0,5 d.

## A5. Timestamp de migración

**Diseño:** `20260110000000_secret_separation` →
`20261109000000_secret_separation`, ordenada antes de las de 0.4. En bases ya
migradas: `npx prisma migrate resolve --applied 20261109000000_secret_separation`
tras comprobar columnas.

**Áreas:** `console/prisma/migrations/**`.

**Aceptación:** `prisma migrate deploy` en base vacía termina y el esquema
coincide.

**Estado:** renombrada y probada sobre una base Postgres 16 vacía en contenedor:
`prisma migrate deploy` aplica las 13 migraciones en orden, termina y
`prisma migrate diff` responde **“No difference detected”**. Se añadió
`20261110000002_uuid_defaults` para retirar defaults UUID que el modelo nunca
declaró y dos índices que la migración inicial creaba y el modelo omitía.
**Esfuerzo:** 0,5 d.

## A6. `keyScheme` por víctima

**Diseño:** el esquema sale de lo declarado en `/enroll`; el build solo es techo.
Un 0.3.7 en build 2 queda `keyScheme=1`, conserva salt/epoch y vuelve a 2 al
re-enrolar con 0.4.

**Áreas:** `routes/agent.ts`, `services/victim.ts`, `CryptoConfig.java`.

**Aceptación:** 0.3.7 sube en build 2; las dos mezclas cubiertas por tests de
confusión de esquemas y downgrade.

**Estado:** hecho. **Esfuerzo:** 0,5 d.

## A7. `panic` con freno

**Diseño:** `POST /panic?dry_run=1` devuelve recuentos sin mutar. El panic real
limpia tokens `prev`/`pending` y estado, conserva salt. El agente mueve la cola
a `held/` y la reintenta al re-enrolar; a los 7 días va a `dead/`. La UI avisa
de que `restore` solo reabre enrolamiento.

**Áreas:** `routes/builds.ts`, `Outbox.java`, `CryptoModule.java`, `builds/page.tsx`.

**Aceptación:** dry-run no muta; tras panic/restore la víctima sigue revocada
hasta re-enrolar; los bucles de heartbeat y tareas no se aparcan, reintentan el
enrolamiento cada 15 s y recogen el restore sin reiniciar el proceso.

**Estado:** hecho. **Esfuerzo:** 0,75 d.

## A8. `key_probe` y matriz

**Diseño:** probe por epoch; tests de downgrade, `MASTER_KEY` en vuelo, probe
manipulado, confusión de esquemas, salt ausente y epoch desconocido.

**Estado:** hecho, 16/16 en `crypto.test.ts`. **Esfuerzo:** 0,75 d.

## A9. `reseal` verificable

**Diseño:** cuenta y falla con código 1 si queda una fila en claro; `/health`
marca `degraded` y el arranque lo registra.

**Aceptación:** con una fila pendiente el panel avisa; al terminar, cero.

**Estado:** hecho. **Esfuerzo:** 0,5 d.

---

## Verificación ejecutada (Linux)

- `./gradlew check`: verde (315 agente, 17 stager, incluidos digest y pins).
- Postgres 16 en contenedor: migraciones aplicadas y **80 tests de consola** y la carrera de enrolamiento añadida en
  verde, incluidos 5 de `/enroll` con dos re-enrolamientos concurrentes, token
  `pending`, token inválido y gracia 0.
- `tsc` cliente y servidor, `next lint`, `next build`: verdes.
- `crypto.test.ts` 16/16 y `contract.test.ts` 5/5.
- `prisma validate` y `generate`: verdes.
- Los 7 escenarios del gate en verde en Linux (1, 2, 5, 6 y 7 con su semántica
  propia; 3 y 4 adaptados a Linux). Falta la ejecución en 23H2/24H2.
- E3: 0 apariciones de las seis cadenas acordadas; `agent.jar` 1.646.040 →
  −8,8 % frente a 1.804.141. No llega al −15 %.
- E2: el test de pin valida la cadena contra un trust store dedicado y rechaza
  el pin erróneo.
- E1: `jarsigner -verify` local devuelve `jar verified` (autofirmado, sin TSA);
  el pipeline exige TSA y verifica el PE con `osslsigncode`.
- Sin Postgres local: los tests de rutas que dependen de DB no se ejecutaron.

## Lo que queda abierto de A

- Nada de A queda sin ejecutar en Linux. La aceptación de Windows de A3/A4 se
  cubre con los escenarios 1, 2 y 6 del gate C.

---

## Tabla de fases actualizada

| Fase | Release | Contenido | Estado | Puerta |
|---|---|---|---|---|
| F0 | 0.3.9 | P0.1–P0.7 | cerrada | verificada en Linux |
| A | 0.4.0 | correcciones A1–A9 | hecha en Linux | Windows vía gate C |
| B | — | `docs/REENROLL.md` | aprobada por diseño | desbloquea A1/A2 |
| C | — | puerta Windows 7×2 | automatizada, sin ejecutar | **bloquea D/E** |
| D | — | contrato v2 y negociación | hecha | corpus dual verde |
| E1 | 0.4.0 | firma estable | build/CI hecho | sin certificado real |
| E2 | 0.4.0 | pinning | agente hecho | sin banco TLS |
| E3 | 0.4.0 | ofuscación | 0 literales; −8,8 % | sin −15 % |
| E4–E11 | 0.4.0 | OPSEC | pendiente | E1–E3 |

## Puertas superadas

- B: documento de estados y decisiones escrito antes de A1/A2.
- D: contrato aditivo, normalizador único y corpus v1⊂v2 en verde.
- A: 9/9 puntos con código y tests; Postgres en contenedor verde. Windows se
  declara pendiente de la ejecución real del gate C (23H2/24H2).

## Fuera de alcance (sin cambios)

F2–F4, `wallet.dat`/scrypt de Exodus, contraseña maestra de Firefox, SYSTEM por
defecto, driver de teclado/red, SRUM en caliente, fronting real, RDP/VNC, móvil,
SSO, facturación, SIEM, Next 15 y TSA en local.
