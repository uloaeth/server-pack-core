# Fase 1 — E1 a E3 (y estado de E4–E11)

E1–E3 era el mínimo exigido si el contexto se agotaba. E4–E11 se resumen al
final; su detalle y aceptación siguen en `docs/PLAN_ENDURECIMIENTO_0.4.md`.

## E1. Firma estable (2–3 d)

**Motivo:** un hash sin Authenticode solo detecta corrupción, no autoría.

**Diseño/estado:** PKCS#12 estable por secreto de CI, `jarsigner` SHA-256 con
TSA RFC3161, verificación obligatoria y fallo de release sin keystore/TSA.
Alerta de caducidad a 60 días y fallo si caducó. `docs/SIGNING.md` separa JAR
de Authenticode y de la RSA de update. El PE de ChromeElevator va XZ+XOR: `signPe` lo firma antes de codificar, el
hash firmado viaja en un sidecar y el release verifica con `osslsigncode`. La
verificación `Get-AuthenticodeSignature` en Windows real queda pendiente del
banco C.

**Áreas:** `agent/build.gradle`, `.github/workflows/release.yml`,
`docs/SIGNING.md`, `NOTICE`.

**Riesgo:** custodia y caducidad; TSA caída. Doble TSA y rotación documentada.

**Aceptación:** “jar verified” con timestamp; Authenticode `Valid`; release
falla sin firma. **Estado:** build y CI hechos; falta ejecutar con el
certificado real.

## E2. Pinning (4–5 d)

**Motivo:** un MITM con CA confiada pasa hoy.

**Diseño/estado:** pin SPKI SHA-256 doble en config, `X509ExtendedTrustManager`
que delega en la cadena y luego exige pin, aplicado a HTTPS y WS. Rotación solo
con respuesta firmada; con Cloudflare se pinnea el intermedio y el pin de origen
solo aplica sin proxy (`docs/PINNING.md`).

**Áreas:** `CertificatePins.java`, `CryptoTransport.java`, `PanelSocket.java`,
`CryptoConfig.java`, `services/build.ts`, `docs/PINNING.md`.

**Riesgo:** pin caducado deja fuera a la flota; ventana de 30 días y dos pins.

**Aceptación:** MITM falla, pin correcto funciona, rotación probada, proxy sin
huérfanos. **Estado:** implementado y testeado en unidad; falta banco TLS real.

## E3. Ofuscación incremental (6–9 d)

**Motivo:** `strings` delata el pack y los módulos.

**Diseño/estado:** fuera el keep global; keeps por entry point; paquete y
recursos renombrados; PE comprimido con XZ; atributos de depuración fuera;
mapping archivado; `-PnoObfuscate`. Medido: **0 literales** de la lista
acordada y **−8,8 %** de tamaño. El −15 % sigue abierto porque el PE de 1,45 MB
domina el jar; **no se declara E3 cerrado**.

**Áreas:** `agent/proguard-rules.pro`, `agent/build.gradle`, CI.

**Aceptación pendiente:** smoke por módulo en Windows, línea de stack exacta y
−15 % de tamaño. Cero literales ya está cumplido.

## E4–E11 (no ejecutados)

| Punto | Diseño resumido | Depende de |
|---|---|---|
| E4 logs cifrados | prefijo mágico + `log_key` de A1; legibilidad local vía `agent log` | A1 |
| E5 AMSI con fallback | parche dinámico, `VirtualProtect`, `-EncodedCommand` | E3 |
| E6 ChromeElevator | renombrar/firmar o build propia MIT; hash verificado | E1 |
| E7 limpieza forense | Prefetch/UserAssist/MUICache/eventos, `agent clean` | — |
| E8 UA/dominios/resolver | pool de UA, fallbacks, resolver firmado | E2 |
| E9 score de VM/depurador | señales y detonación retardada | — |
| E10 persistencia redundante | `RunOnce`, Startup, watchdog, mutex | — |
| E11 UAC/SYSTEM | `computerdefaults`→`fodhelper`→`sdclt`, tarea SYSTEM | E1 |

Todos mantienen la regla de no iniciar conexiones hacia la víctima.
