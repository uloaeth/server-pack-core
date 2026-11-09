# Re-enrolamiento e identidad de dispositivo (bloque B)

Estado: **propuesto para 0.4.0**. Bloquea A1 y A2. Implementación en
`console/server/services/victim.ts` y `contract/v2/` (campos aditivos).

## 1. Problema

`/enroll` solo autentica el build: el `enroll_token` es determinista, universal
y no demuestra posesión de nada. La identidad de víctima es
`(build_id, hostname, username)`. Consecuencias actuales:

- Quien tenga el jar y acierte host y usuario **rota** el `device_token` de la
  víctima real. El agente real recibe 401, vuelve a enrolar y ambos se pisan.
- El mismo `/enroll` **reemite el salt**; aunque la cola cifrada sobreviva, deja
  de poder abrirse. P0.4 existe justo para el caso de re-enrolamiento.
- La frase “un jar filtrado sin salt no abre artefactos ajenos” es cierta solo
  mientras el atacante no haya enrolado antes. Tras un enrolamiento ajeno, el
  atacante recibe el salt de la víctima.

La identidad sigue siendo débil por diseño: no hay secreto de hardware. Lo que
se añade es **prueba de posesión del token anterior** y una **gracia acotada**.

## 2. Principios

1. El salt es de la **identidad**, no del enrolamiento. Solo una rotación
   explícita de admin lo cambia (A1).
2. Un enrolamiento sin prueba **nunca** invalida el token vigente.
3. La gracia es un estado explícito, de 24 h por defecto, auditado y
   desactivable por build (`Build.enrollGraceHours = 0`).
4. Todo camino conserva la cola y el epoch; ninguno borra claves locales.
5. El servidor no inicia conexiones: la confirmación siempre la provoca una
   petición firmada del agente.

## 3. Estados de identidad

| Estado | Significado | Token vigente | Salt |
|---|---|---|---|
| `new` | No existe fila; el build está vivo | — | nuevo al crear |
| `confirmed` | El llamante probó posesión del token vigente | `active`, rotado | se conserva, `keyEpoch` intacto |
| `unverified` | Primer enrolamiento de una identidad nueva | `active` (el emitido) | nuevo, epoch 1 |
| `grace` | Enrolamiento sin prueba sobre una identidad ya confirmada | `active` sigue siendo el real; `pending` es el nuevo | se conserva, mismo epoch |
| `revoked` | Panic o revocación | ninguno válido; `prev`/`pending` se limpian | se conserva |
| `stale` | Estado de presencia, no de identidad | sin cambio | sin cambio |

`unverified` y `grace` comparten reloj de gracia (`graceUntil`). No hay un
tercer estado “pendiente de admin”: la caducidad resuelve sola.

## 4. Caminos y tokens

### Camino 1 — `/enroll` con `device_token` válido

1. Se verifica contra `active` o `prev` (gracia de rotación, 24 h).
2. `active` pasa a `prev` con caducidad de 24 h; se emite un `active` nuevo y se
   limpia cualquier `pending`.
3. Mismo `keySalt`, mismo `keyEpoch`, `status = confirmed`.
4. Evento `enroll_confirmed`; la cola local no cambia de clave.
5. Es el camino normal de heartbeat 401 → re-enrolar y el de `panic/restore`.

### Camino 2 — `/enroll` sin token, identidad existente

Con gracia activa:

1. Se emite un token `pending`, caducidad `now + graceHours`.
2. `active` **sigue vigente**. El agente real continúa firmando con él. La
   actualización de gracia no reescribe `deviceTokenEnc`, de modo que un
   enrolamiento confirmado concurrente conserva su rotación.
3. Mismo salt y epoch. Evento `unverified_enroll` con IP, versión y agente.
4. Si el agente real vuelve con `active`: estado `confirmed`, `pending` se
   invalida, evento `enroll_confirmed`.
5. Si nadie confirma y vence la gracia: `pending` pasa a `active`, el anterior
   se descarta y el estado queda `unverified`. Un barrido perezoso en cada
   petición y en `/enroll` aplica la promoción; no hay cron obligatorio.

Con gracia desactivada (`enrollGraceHours = 0`): comportamiento histórico, el
token emitido sustituye al vigente y el anterior caduca en el acto.

Identidad nueva (sin fila): el token emitido es `active`, estado `unverified`.
La primera petición firmada lo confirma. No hay `pending` que invalidar.

### Autenticación con token pendiente

`authenticateVictim` acepta `active`, `prev` (si no venció) y `pending` (si no
venció). Una petición firmada con `active` sobre un estado `grace` confirma y
limpia `pending`. Una petición con `pending` **no** confirma por sí sola: el
atacante también puede firmar. Esto es deliberado; si confirmara, un tercero
seguiría expulsando al agente real.

## 5. Prohibiciones del camino 1

- Token presente pero inválido y sin coincidencia de identidad → `401
  bad_device_token`. No se degrada a camino 2 en la misma petición para que el
  agente sepa que debe re-enrolar.
- Identidad existente pero build distinto → fila nueva (la clave es
  `build_id + hostname + username`).
- Build revocado → `403 build_revoked` antes de tocar tokens.
- Víctima revocada con build restaurado → se permite el camino 1 o 2; el
  enrolamiento es lo único que des-revoca la fila.

## 6. Gracia y `panic`

- `panic` revoca builds y víctimas en una transacción y limpia `prev`/`pending`
  y `graceUntil`. No toca salt, epoch ni `keySaltEnc`.
- `panic/restore` solo reabre builds. Una víctima sigue `revoked` hasta que
  vuelve a enrolar; la UI lo dice y resta un “restore víctima” implícito.
- Tras un panic, un tercero con host/usuario puede abrir una gracia de 24 h
  sobre una fila revocada. El agente real, si sigue vivo, no puede firmar (410);
  al relanzarse usa el mismo camino y puede confirmar con su token. Los bucles
  de heartbeat y tareas siguen vivos durante el 410 y reintentan el
  enrolamiento cada 15 s, de modo que `panic/restore` se recoge sin reiniciar.
  Mientras tanto el salt no cambia y la cola local sigue legible.
- La gracia no resucita un build revocado.

## 7. Cola y epochs en cada transición

| Transición | `keyEpoch` | Outbox | Artefactos en vuelo |
|---|---|---|---|
| `new` → `unverified` | 1 (o el vigente) | se cifra con el epoch activo | — |
| `confirmed` (camino 1) | sin cambio | intacta | válidos |
| camino 2 (`grace`) | sin cambio | intacta; los envíos firmados con `active` funcionan | válidos |
| vence la gracia | sin cambio | intacta | válidos |
| rotación de salt | +1 | nuevas entradas al epoch nuevo; las viejas se drenan con historial (máx. 3) | llevan `key_epoch`; el servidor deriva por epoch |
| epoch evadido del historial | — | a `dead/unknown_epoch` | el servidor responde `422 key_epoch_unknown` |
| `revoked` (410) | sin cambio | entradas a `held/`; se reintentan tras re-enrolar; a `dead/` a los 7 días | commit sigue aceptando el epoch |
| borrado de víctima | — | el agente conserva su copia local | — |

## 8. Modelo de datos

- `Victim.keyEpoch Int @default(1)`.
- `Victim.prevTokenEnc`, `prevTokenExpiresAt`, `pendingTokenEnc`,
  `pendingTokenExpiresAt`, `graceUntil`, `identityState`.
- `VictimKeyEpoch(victimId, epoch, saltEnc, createdAt, retiredAt?)`, único por
  `(victimId, epoch)`. Se conservan las 3 últimas; el barrido retira la cuarta.
- `Build.enrollGraceHours Int @default(24)`; `0` desactiva.
- `Victim.keySaltEnc` se mantiene espejando el epoch activo por compatibilidad
  con el código 0.3.9.

## 9. Contrato

Aditivo sobre v1 (campos opcionales que un 0.3.7 ignora):

- `/enroll` acepta `device_token?` y `key_epoch?`.
- `/enroll` responde `key_epoch` y `identity_state`.
- `/hb` responde `key_epoch` y, solo si el epoch subió, `key_salt` + `key_epoch`.
- `GET /capabilities` (v2) devuelve `contract: 2`, `key_epoch` y la intersección
  de capabilities.

## 10. Aceptación

1. Dos re-enrolamientos simultáneos sin token dejan `active` vigente y un solo
   `pending`; el agente real no queda sin token.
2. Un tercero sin token no rompe al agente real: este sigue firmando durante la
   gracia y al volver confirma.
3. El salt y el epoch no cambian en ningún camino salvo rotación explícita.
4. `unverified_enroll` y `enroll_confirmed` quedan en `events` con IP y versión.
5. La gracia vence y promociona `pending` sin intervención.
6. `enrollGraceHours = 0` reproduce el comportamiento de sustitución inmediata.
7. Tests puros de la máquina de estados y de autenticación con los tres tokens.

## 11. Riesgos y límites

- La gracia es la nueva superficie: 24 h es un compromiso entre comodidad y
  exposición. Se audita y se puede desactivar por build.
- Sin secreto de hardware, un atacante con host/usuario y paciencia puede quedar
  como `pending`; no obtiene descifrado si no conoce el salt, pero sí puede
  denegar servicio tras la promoción.
- El token `pending` no confirma para no reintroducir la denegación actual.
- La rotación de salt es una operación de admin, auditada, y no automática.
