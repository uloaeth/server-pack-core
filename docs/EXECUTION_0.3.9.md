# Ejecución 0.3.9 — F0 y decisión de secretos

**Objetivo:** indetectabilidad. F1 manda; F2 espera. **Equipo:** 2 personas
(1 Java/Windows internals, 1 full-stack/CI). **Calendario:** F0 cerrado en esta
entrega; F1 arranca en la siguiente, en el orden P1.1 → P1.9 → P1.2.

## Decisión bloqueante aplicada

Ver `docs/SECRETS.md`. `enroll_token` y `artifact_key` ya no comparten
derivación; `key_salt` es por dispositivo; `build_secret` se guarda sellado y el
esquema 1 sigue operativo para agentes instalados. Tests: claves distintas por
salt, token distinto de la clave, apertura de secreto legacy/sellado.

## Estado por punto

| Punto | Estado | Evidencia |
|---|---|---|
| P0.1 hash de `agent.bin` | Hecho | `verifyAgentBin`, `agent.sha256` canónico (excluye META-INF), stager verifica al instalar/lanzar, CI valida contra `agent.jar`, test de manipulación y dos firmas con hash idéntico |
| P0.2 versión única | Hecho | `version.txt`; mapa legacy único en `agent-compat.ts`; `verifyVersions` implícito por fuente única y tag |
| P0.3 sin `wmic` | Hecho | `Cim.java`; GamePid/Bootstrap/acciones migrados; `cim_unavailable` no fatal |
| P0.4 outbox | Hecho | `Outbox.java` cifrado SPCB1, AAD, backoff 2 s–5 min, jitter, 20 intentos, poda, 409/410 terminales, reanudación por wake |
| P0.5 preflight | Hecho | validación local, comparación de manifiesto, `key_probe`, 422 tipado con IDs, evento y toast |
| P0.6 telemetría | Hecho | eventos deduplicados, umbral 5, badge de salud, `/diagnostics`, contador 24 h |
| P0.7 cancelación | Hecho | `Budget` de harvest, ZIP parcial, ransomware streaming `.part`, monitor 3,5 MiB, `timeout`≠`cancelled` |
| P0.8 `hs_err_pid` | Hecho | `CrashReports`, `-XX:ErrorFile`, evento y harvest <1 MB |
| P0.9 uninstall | Hecho | `agent uninstall [--keep-data]`, borrado diferido, recuento y fallo limpio |
| P0.10 panic | Hecho | `/panic` y `/panic/restore` transaccionales, 410 en el siguiente heartbeat |

## Verificación ejecutada

- `./gradlew check` en Java 21: verde (305 tests de agente y 16 de stager, incluido hash canónico reproducible y manipulación).
- `npx tsc --noEmit` y `tsc -p server/tsconfig.json`: verdes.
- `prisma validate` y `next lint --max-warnings=0`: verdes.
- `crypto.test.ts`: 8/8, incluida separación de claves.

## Pendiente inmediato

- P1.1 firma estable: el hash de P0.1 solo cubre corrupción hasta que exista
  Authenticode y sello de tiempo.
- Repetir P0.4/P0.5 contra un panel real y una VM Windows 11 antes de release.
- Puerta v2 (`contract/v2`) antes de cualquier capacidad de F2.
