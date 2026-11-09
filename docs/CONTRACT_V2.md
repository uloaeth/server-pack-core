# Puerta de contrato v2 (bloque D)

Estado: **abierta**. Solo sobre y negociación; ningún frame de capacidad se
adelanta a su feature.

## Diseño

- `contract/v2/` es copia de v1. Solo añade campos y tipos; ningún endpoint,
  método ni semántica v1 cambia. `/enroll` acepta `device_token`, `key_epoch` y
  `capabilities`; responde `contract: 2`, `identity_state`, `key_epoch` y la
  intersección.
- `GET /capabilities` autenticado refresca la intersección. `X-Contract: 2` es
  refuerzo: su ausencia significa v1 y un 0.3.7 opera todo v1 contra 0.4.
- `Victim.capabilities` es la intersección declarada ∩ soportada, persistida;
  `Victim.contractVersion` se queda en 1 si el agente no declara v2. Un
  capability declarado pero no implementado se descarta.
- Capacidad no implementada → `501 capability_not_implemented` con
  `victim_ids`. El panel no pinta una acción cuya capability no esté en la
  intersección.
- Un único normalizador (`server/lib/agent-contract.ts`) y el mismo corpus
  OpenAPI contra v1 y v2 (`tests/contract.test.ts`).

## Áreas

`contract/v2/**`, `contract/v1/**` (intacto), `server/lib/agent-contract.ts`,
`routes/agent.ts`, `routes/tasks.ts`, `schema.prisma`, migración
`20261110000001_contract_v2`, `CryptoTransport.java`, `CryptoModule.java`,
`src/lib/actions.ts`, `docs/CONTRACT_V2.md`.

## Riesgos y mitigación

- Doble parser: un solo normalizador y test de corpus dual.
- Divergencia de esquema: el test exige que todo path/método/campo v1 exista en
  v2 y que ningún `required` desaparezca.
- Capacidad mentida: la intersección se recalcula en cada `/enroll`/`/hb`; el
  servidor no confía en la declaración.

## Aceptación

| Caso | Resultado esperado | Estado |
|---|---|---|
| 0.3.7 contra servidor 0.4 | opera todo v1; `contractVersion=1`; sin campos v2 | cubierto por normalizador y contrato aditivo; falta prueba con binario real |
| 0.4 contra servidor 0.3.7 | degrada a v1; oculta capacidades | diseño; falta binario/servidor 0.3.7 en el banco |
| Panel con capability ausente | no pinta la acción | `actionReason` + `/tasks` 501 |
| Corpus dual | v1 ⊂ v2 y sin campos perdidos | 5/5 en `contract.test.ts` |
| Capacidad no implementada | `501 capability_not_implemented` | implementado |

## Límite actual

`SUPPORTED_CAPABILITIES` está vacío porque F2 no ha empezado: la intersección
real es `[]` y ninguna acción v2 se pinta todavía. Es el estado correcto para
no ofrecer lo que el agente no ejecuta.
