# Separación de secretos (decisión bloqueante)

Estado: **aplicada en 0.3.9, revisada en 0.4.0**. Esquema 1 se conserva para
agentes ya instalados; epochs y gracia son aditivos.

## Problema

`build_secret` hacía dos trabajos incompatibles:

1. token de enrolamiento, guardado hasheado;
2. clave de artefactos, que el servidor debe poder abrir.

Un jar filtrado permitía descifrar cualquier artefacto del build y el token de
enrolamiento compartía el mismo material.

## Diseño

- `enroll_token = HKDF-SHA256(build_secret, salt=build_id, info="SPC-ENROLL-V2")`.
  El servidor guarda solo `sha256(enroll_token)`.
- `artifact_key = HKDF-SHA256(prk, salt=key_salt, info="artifact-v2:<victim_id>")`,
  con `prk = HKDF-SHA256(build_secret, salt=build_id, info="SPC-ART-V2")`.
- `key_salt` son 32 bytes aleatorios **por dispositivo e identidad**, generados
  una sola vez y sellados con `MASTER_KEY` (`Victim.keySaltEnc` +
  `VictimKeyEpoch.saltEnc`). Un re-enrolamiento **no** reemite el salt. Sin el
  salt, un jar filtrado no abre artefactos ajenos; sí puede provocar una
  denegación temporal (ver “Identidad débil”).
- `artifact_key`, `outbox_key` y `log_key` derivan del mismo PRK con `info`
  distintos (`artifact-v2`, `outbox-v2`, `log-v2`) y el epoch en el label. Las
  tres claves difieren para la misma víctima y época. El epoch 1 conserva el
  label original para leer artefactos 0.3.9.
- `build_secret` se guarda sellado con `MASTER_KEY` (`Build.secret`).
- `Build.enrollScheme` y `Victim.keyScheme` separan generaciones. Un build
  legacy sigue en 1 y nunca mezcla derivaciones.
- El contenedor lleva `key_probe` para que el servidor rechace una clave errónea
  en el commit, antes de procesar.

## Migración

1. Las filas existentes conservan `enrollScheme=1`, `keyScheme=1` y su
   `secret` en claro hasta ejecutar `npx tsx scripts/reseal.ts`, que también
   sella los secretos legacy sin cambiar su valor ni el token. La derivación
   antigua sigue funcionando sin cambios.
2. Los builds nuevos nacen en esquema 2 (`secret` sellado, token HKDF).
3. Un agente 0.3.9 sobre un build 1 pide esquema 2, pero el servidor lo fuerza a
   1. Un agente 0.3.9 sobre un build 2 recibe el salt ya existente (o uno nuevo
   solo la primera vez).
4. Una víctima existente que vuelve a enrolar con un agente sin soporte pasa a
   esquema 1 pero **conserva** el salt y su epoch; con 0.4 vuelve a 2 con el
   mismo salt. El esquema se fija por víctima según lo declarado en `/enroll`;
   `Build.enrollScheme` solo es el techo.

## Epochs

`Victim.keyEpoch` empieza en 1. Cada rotación explícita de admin crea un epoch
nuevo y un salt nuevo en `VictimKeyEpoch`; se conservan los tres últimos. El
agente guarda el mismo historial (máximo 3) y las entradas de outbox llevan el
epoch en el nombre y la cabecera, así que una cola vieja se cierra con la clave
de su epoch. Una entrada cuyo epoch ya no está en el historial va a
`outbox/dead` como `unknown_epoch`. La rotación es `POST
/victims/:id/rotate-key` (admin, auditada) y nunca se dispara sola.

## Identidad débil (A2)

Máquina de estados completa: `docs/REENROLL.md`.

`enroll_token` sigue siendo determinista y universal: **no autentica** al
dispositivo. La identidad real es `(build_id, hostname, username)`, que un
atacante puede conocer. Por eso el enrolamiento tiene dos caminos:

1. Con `device_token` válido: posesión probada, mismo salt/epoch, rotación de
   token y `enroll_confirmed`.
2. Sin token: se emite un token `pending` y el vigente sigue válido durante
   `Build.enrollGraceHours` (24 h por defecto, 0 lo desactiva). El agente real
   sigue firmando con el token viejo; si vuelve, confirma y el `pending` se
   invalida. Si no vuelve, el `pending` se promociona al vencer.

Consecuencias honestas:

- Un jar filtrado **sí** permite denegación sobre equipos cuyo `hostname` y
  `username` se conozcan: puede abrir una gracia y, tras 24 h sin que el agente
  real confirme, quedarse con el token.
- **No** permite descifrar artefactos si el salt no se filtró antes. Tras un
  enrolamiento ajeno, el atacante recibe el salt (el servidor se lo devuelve)
  pero no el `build_secret` de otro build.
- El token `pending` que firma por sí mismo **no** confirma, para no reintroducir
  la denegación actual.
- La gracia es superficie: se audita (`unverified_enroll`) y es desactivable por
  build.

## `key_probe`

Por epoch: `key_probe = base64url(HMAC-SHA256(artifact_key_epoch, "probe"))`. No
es material reversible y no sustituye al GCM: solo permite rechazar una clave
equivocada antes del procesado asíncrono. Un probe manipulado da `422
artifact_key_mismatch`; un `key_epoch` mayor que el de la víctima da `422
key_epoch_unknown`.

## Rotación de MASTER_KEY

Ventana de convivencia: `MASTER_KEY` nueva y `MASTER_KEY_OLD` en el entorno.
`openSecret` prueba actual y anterior. `npx tsx scripts/reseal.ts` re-sella
`Build.secret`, `Victim.deviceTokenEnc`, `Victim.prevTokenEnc`,
`Victim.pendingTokenEnc`, `Victim.keySaltEnc` y `VictimKeyEpoch.saltEnc`; al
terminar se retira la vieja. Nunca se rota durante un rollout de builds.

`reseal.ts` cuenta filas, no re-sella las ya selladas y **falla con código 1** si
queda alguna en claro. El panel arranca en modo degradado explícito
(`/health.degraded` y log `DEGRADED`) mientras `sealing_pending > 0`.

## Verificación

- Dos víctimas del mismo build tienen claves distintas (test `crypto.test.ts`).
- Las claves de artefacto, outbox y log difieren; un epoch nuevo cambia clave y
  probe; un scheme-2 sin salt no degrada en silencio.
- Un jar filtrado sin salt no deriva la clave de otra víctima.
- Un agente 0.3.7 sigue subiendo con `keyScheme=1` y conserva el salt.
- La matriz cubre downgrade de esquema, MASTER_KEY en vuelo, probe manipulado,
  confusión de esquemas y epoch desconocido.
