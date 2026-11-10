# Menú de módulos y tema XP — auditoría e integración

Rama: `fix/0.3.7-rat-hardening`.

## 1. Auditoría de los cambios previos (Nemotron)

Estado encontrado: trabajo a medias, sin compilar. Nada estaba cableado al panel.

### Qué estaba bien y se conservó

- La idea de un registro único (`console/src/lib/modules.ts`) con categorías,
  disponibilidad por víctima y resolución de tareas es correcta.
- El CSS XP tenía tokens y clases para menú/diálogo: buen punto de partida.
- El menú y el diálogo ya contemplaban buscador, estados y descarga.

### Qué estaba roto y cómo se corrigió

| Archivo | Problema | Corrección |
| --- | --- | --- |
| `globals.css` | Tokens HSL con `218/255`: `hsl()` interpreta el número como porcentaje, así que el fondo quedaba casi negro. | Tokens nuevos en `H S% L%` reales y modo oscuro completo. |
| `globals.css` | Clases XP sin `border-style`, botones sin reset y `app-region` sin efecto. | Reescritura de menú/diálogo/ventana con biseles, foco y scroll clásicos. |
| `modules.ts` | `versionAtLeast` duplicado de forma divergente, `btoa` fallaba con UTF-8, `files` enviaba `path: ""` (rechazado por el contrato), textos fuera del catálogo. | Comparador único, Base64 UTF-8, rutas válidas por SO, las 23 etiquetas exactas del §2. |
| `modules.ts` | `moduleAvailability` devolvía historial sin tipar y el menú no podía reabrir nada. | `history` tipado; el menú expone el último resultado reabrible. |
| `module-menu.tsx` | `setExpanded(string)` contra `ModuleCategoryId`, `title` con `null`, iconos duplicados, `renderModule` con índice muerto, sin cablear. | Estado `Set<SectionId>`, tipos corregidos, imports limpios y cableado real. |
| `module-menu.tsx` | El menú solo tenía módulos y perdía las acciones clásicas (energía, cifrado, etc.). | Bloque «Acciones del panel» con el 100 % de `ACTIONS`, agrupado y deshabilitado con motivo. |
| `module-result-dialog.tsx` | `loadHistory` usaba `selectHistoryTask` antes de declararlo (TDZ), comparaba `mode === 'history'` inexistente, `retry` no reejecutaba, no había artefactos ni foco. | Diálogo reescrito: orden de hooks, reintento real, streaming, artefactos, descargas, foco atrapado y Escape. |
| `operations-view.tsx` | `openMenu` renombrado a `openModuleMenu` pero seguía referenciado; `ModuleMenu` importado sin renderizar. | Cableado completo al clic derecho y a `Shift+F10`. |

### Regresiones comprobadas

- `ActionMenu` sigue existiendo (y sus tests); el menú nuevo lo sustituye en la
  operación, pero conserva todas sus acciones en «Acciones del panel».
- Paleta de comandos, detalle de víctima, tareas, vault y ficheros no se tocaron
  salvo los estilos globales.
- No se importó código de servidor en componentes cliente: `modules.ts` solo
  importa tipos y helpers puros.

## 2. Plan de integración aplicado

1. Registro único de 23 módulos con seis categorías y contrato tipado.
2. Menú contextual XP con las seis categorías abiertas, buscador, estados,
   motivos, historial reabrible y bloque de acciones clásicas.
3. Modal de resultado con output crudo, streaming por polling, metadatos,
   descarga `.txt`/`.log`, artefactos binarios y reintento.
4. Endpoint `GET /tasks/:id/artifacts` y MIME/inline en `/artifacts/:id/file`.
5. Tema XP por tokens, con variante oscura y conmutador persistente.
6. Verificación: `tsc`, `next lint`, `next build`, `tsc` del servidor y tests.

## 3. Puntos que dependen del protocolo del cliente

- `monitor` entrega `frames[{ts,data}]` en base64; el control de ratón/teclado
  no existe en el contrato v1, solo la captura.
- `webcam` y `mic` requieren agente ≥ 0.3.7 y `ffmpeg`; suben un artefacto que
  el servidor vincula a la tarea al cerrar el resultado.
- `offline-keylogger` usa `agent keys on|off|status|dump` y el modal añade un
  panel en vivo que sondea `/victims/:id/keylogs` cada segundo.
- `HVNC`, Embedded Browser, File Recovery, Geolocation, Process Hiding,
  UAC Bypass, Bootkit, Binder y Jar Injector no tienen backend: se muestran
  atenuados, con motivo, y su modal devuelve `not_implemented` con reintento.
- El streaming incremental es polling de tareas (700 ms): el WS del panel solo
  publica `task.update` sin payload. Si el agente añade `task.output`
  incremental, el visor lo pinta sin cambios de estructura.
- `file-manager` abre el listado raíz vía `files`; el CRUD completo sigue en la
  pestaña Ficheros del detalle.

## 4. Evidencia visual

- `docs/ui/xp-victims.png`: listado con barra de título y estado.
- `docs/ui/xp-module-menu.png`: las 6 categorías y los 23 módulos.
- `docs/ui/xp-module-unsupported.png`: modal de módulo sin backend con reintento.
- `docs/ui/xp-keylogger.png`: captura en vivo y consola cruda.
- `docs/ui/xp-module-menu-dark.png`: variante oscura con la misma estructura.
- `docs/ui/xp-tasks.png`: otra vista con el mismo tema.

## 5. Cómo ampliar el catálogo

Añadir una entrada a `MODULE_REGISTRY` y, si necesita otro grupo, a
`MODULE_CATEGORIES`. El menú, el modal, la disponibilidad, el historial y el
nombre de descarga se derivan automáticamente. No hay que tocar componentes.
