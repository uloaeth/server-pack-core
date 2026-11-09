# Puerta Windows (bloque C)

Estado: **automatizada, pendiente de ejecución real en 23H2 y 24H2**. Linux no
sustituye esta puerta; los escenarios se reproducen también en Linux cuando es
posible.

Workflow: `.github/workflows/windows-gate.yml` (`windows-2022` y `windows-2025`,
matriz de siete escenarios). Runner: `tools/windows-gate.ps1`; lógica:
`WindowsGate` en el classpath de test del agente.

| # | Escenario | Montaje | Invariante | Cómo falla |
|---|---|---|---|---|
| 1 | Perfil en otro volumen | VHD (fallback `subst`) | el outbox escribe, descifra y barre en el volumen B | `PASS` ausente |
| 2 | `ATOMIC_MOVE` forzado a fallar | `spc.forceNonAtomic` | degrada a `fsync` + rename y marca `flags=1`; el temporal huérfano se barre | flag 0 o entrada ilegible |
| 3 | PowerShell bloqueado / CLM | `LanguageMode=ConstrainedLanguage` | CIM devuelve vacío, `cim_unavailable`, sin excepción | excepción o cuelgue |
| 4 | `uninstall` con ficheros abiertos y carpeta parcial | proceso aparte, `runtime.jar` abierto | borra en diferido y `--keep-data` conserva `cache` | fichero restante |
| 5 | `hs_err` generado, movido y filtrado | dump sintético | aparece `.filtered` con `%USERPROFILE%` | dump sin mover o sin filtrar |
| 6 | `MANIFEST.MF` y `META-INF/services` alterados | ZIP sintético | el hash canónico cambia | hash igual |
| 7 | `.version` manipulado y hash alterado | instalación sintética | reinstala con versión u hash malos | `isInstalled` true |

## Criterio de cierre

Los siete escenarios en verde en **ambas** versiones. Con uno en rojo, D y E no
abren. Los artefactos de log se suben incluso en fallo.

## Riesgos del banco

- Hyper-V puede no estar disponible en el runner: el fallback `subst` no crea
  un volumen distinto, así que el escenario 1 se marca con `Warning` y el
  escenario 2 cubre la semántica del fallo con la propiedad forzada.
- `uninstall` usa `sleep 3`; el gate espera hasta 15 s para no ser flaky.
- El antivirus del runner puede retrasar la primera ejecución de `javaw`; los
  timeouts del gate son holgados a propósito.
