# Ofuscación incremental (E3)

Estado: **pasos 1–3 aplicados**. El criterio de tamaño aún no llega al −15 %.

## Regla incremental

Un cambio por PR, smoke completo en cada paso y `mapping.txt` archivado. Nunca
se ofusca el mod; solo el agente standalone. `-PnoObfuscate` publica el jar
plano para diagnóstico y no altera la firma.

## Aplicado

- Fuera el `-keepclassmembers class ** { *; }` global.
- Keeps explícitos: manifest/entry points (`Agent.main`, `Premain`, `Json`,
  `Bootstrap`, `BuildInfo`, `Version`, `PanelSocket`, `Q1`/`Q2`), interfaces
  JNA cuando existan.
- Paquete `com.serverpackcore.agent` → `com.spcagentpayloa.jvmxx` (misma
  longitud) y recursos `serverpackcore/` → `spcagentpayloa/`; el PE pasa a
  `c.dat`, las clases de red a `Q1`/`Q2`.
- `-repackageclasses 's'`, `-allowaccessmodification`,
  `-overloadaggressively`, 3 pasadas.
- Atributos de depuración (`SourceFile`, `LineNumberTable`) fuera en release;
  el mapping sigue desofuscando clases y métodos.
- PE comprimido con XZ antes del XOR (dependencia `org.tukaani:xz` incluida y
  reducida por ProGuard).
- `-printmapping build/mapping.txt` y copia a `agent-mapping.txt` en el release.

## Métrica medida (Linux, 0.4.0-dev)

| Métrica | Antes de E3 | Después |
|---|---|---|
| Tamaño `agent.jar` | 1.804.141 B | 1.646.040 B |
| Reducción | — | **8,8 %** |
| `strings` de la lista acordada | 60+ | **0** |

Lista acordada comprobada: `serverpackcore`, `FabricSync`, `ServerPackCore`,
`chromelevator`, `Discord`, `DPAPI`.

## Pendiente para cerrar E3

1. Llegar al −15 %: el PE de 1,45 MB domina el jar. Medido en Linux:
   - XZ preset 6/9/9e: 1.228.300 / 1.228.300 / 1.228.392 B (plano).
   - `objcopy --strip-all`: sin cambio (el PE no lleva tabla de símbolos).
   - UPX 5.3 `--best`: el PE baja a 1.369.088 B, pero al pasarle XZ ocupa
     1.330.404 B, **más** que sin UPX: empeora el jar final.
   El −15 % exige rebuild del PE (Go `-ldflags "-s -w"` o equivalente) o cambiar
   el contenedor; no se cierra con flags de compresión. Si se empaqueta el PE,
   Authenticode debe aplicarse al binario final y XZ después.
2. Smoke por módulo (`--smoke`, DPAPI, WebSocket, outbox) en cada paso y en
   Win11 23H2/24H2.
3. Confirmar que ningún keep reaparezca por comodidad.

## Aceptación

- Cero literales de la lista acordada: **cumplido**.
- `--smoke` y la suite completos: **cumplido en Linux**; falta Windows.
- Stack de prueba desofuscado con el mapping: el mapping cubre clases y
  métodos; sin `LineNumberTable` no hay línea exacta.
- Tamaño ≥ 15 % menor: **no cumplido (8,8 %)**; no se declara cerrado.
