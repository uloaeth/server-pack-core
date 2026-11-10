# Auditoría de interfaz y conexión del panel

Fecha: 2026-11-10 · Rama: `fix/0.3.7-rat-hardening`
Alcance: `console/src`, `console/server`, rutas `/api/console/ws` y contratos de datos del panel.
Estado: hallazgos registrados antes de corregir. Se actualiza el estado al cerrar cada bloque.

## P0 · rompen la operación

| ID | Hallazgo | Evidencia | Estado |
| --- | --- | --- | --- |
| C-01 | `/seeds` pide `limit=500` y la API valida máx. 300: la página entera queda en error y vacía. | `src/app/(console)/seeds/page.tsx:24`, `server/routes/misc.ts` | corregido |
| C-02 | El WS del panel no tiene heartbeat de aplicación ni watchdog: una caída silenciosa deja la UI en «live» para siempre y las acciones se habilitan contra un socket muerto. | `src/hooks/useLive.ts`, `server/index.ts:124` | corregido |
| C-03 | Con sesión caducada el WS se reconecta en bucle (cierre 4401) y la UI no vuelve al login; los `401` de la API solo pintan texto. | `wsGuard`, `src/lib/api.ts` | corregido |
| C-04 | `setOnline` del `LiveProvider` no hace nada (devuelve siempre el mismo Set): el contador online del contexto está muerto y arranca en 0. | `src/components/live-provider.tsx:32` | corregido |
| C-05 | Los secretos de ficheros de semillas se guardan como `private_key`: contador `seeds` a 0 con 3 semillas DECRYPTED. | DB `WalletSecret`, `normalizeSecretKind` | corregido |
| C-06 | `balance: 3` cuenta `"0 ETH"` como saldo porque compara con la cadena `'0'`. | `server/routes/vault.ts` counters, `wallet-table.tsx` | corregido |
| C-07 | La vista móvil está rota: sidebar fijo de 232 px sobre 420 px, contenido clipeado y sin menú. | captura 420 px | corregido |
| C-08 | `VaultResponse`/UI asumen que todo lo encontrado es wallet: no hay apartados de semillas ni claves privadas y `seed_file` aparece como wallet con balance. | `vault-list.tsx`, `vault/page.tsx` | corregido |

## P1 · degradan la operación

| ID | Hallazgo | Evidencia | Estado |
| --- | --- | --- | --- |
| C-09 | `useApi` no aborta peticiones ni limpia datos al cambiar de víctima: respuestas viejas pisan a las nuevas y se ven datos de otro equipo. | `src/hooks/useApi.ts` | corregido |
| C-10 | El polling sigue a pleno ritmo con la pestaña oculta; no hay reintento con backoff ni estado «obsoleto». | `useApi`, varias vistas | corregido |
| C-11 | El feed «en vivo» del dashboard no es live: `EventFeed` no consume el contexto, solo polling cada 15 s. | `event-feed.tsx` | corregido |
| C-12 | Errores de red/5xx sustituyen toda la tabla por un `ErrorState`, perdiendo los datos ya cargados. | `operations-view.tsx`, `vault-list.tsx` | corregido |
| C-13 | No se distingue `401` de `403`, `429`, `agent_offline` ni fallo de red en la UI; mensajes genéricos en inglés. | `api.ts`, `events` | parcial |
| C-14 | `chainOf` clasifica `exodus` como `other` y `phantom` como `sol` sin comprobar la dirección; agrupa mal la bóveda. | `server/routes/vault.ts` | corregido |
| C-15 | La salud de módulos se pinta como un `!` sin explicación, sin módulo ni antigüedad, y sin leyenda. | `victim-table.tsx` | corregido |
| C-16 | `ago()` y medio panel están en inglés («never», «now», «Nothing unread», «Queue task»). | `format.ts`, `topbar`, `settings` | corregido |
| C-17 | Fechas con `en-GB` en una consola en español. | `format.ts` | corregido |
| C-18 | Iconos de SO con glifos `⊞`/manzana vacía que renderizan como cajas. | `format.ts` | corregido |
| C-19 | Borrado con `window.confirm` nativo; el resto de diálogos son Radix. | `operations-view.tsx` | parcial |
| C-20 | Sesión de logs: refresca eventos y tareas en cada evento y duplica trabajo con el polling. | `session-log.tsx` | corregido |

## P2 · pulido y accesibilidad

| ID | Hallazgo | Evidencia | Estado |
| --- | --- | --- | --- |
| C-21 | `html lang="en"` en una UI en español. | `app/layout.tsx` | corregido |
| C-22 | Sin skip-link ni `aria-current` en la navegación. | `sidebar.tsx`, layout | corregido |
| C-23 | Grids fijos (`grid-cols-7`, `grid-cols-[1fr_360px]`) sin breakpoints. | `counters.tsx`, `dashboard` | corregido |
| C-24 | Mapa con teselas claras sobre tema oscuro. | `globals.css` | corregido |
| C-25 | El detalle no atrapa el foco ni devuelve el foco a la fila al cerrar. | `operations-view.tsx` | corregido |
| C-26 | Estados vacíos/cargando con textos mezclados y sin acción contextual. | varias vistas | corregido |
| C-27 | La bóveda global no pagina por cursor real: el botón «siguiente» solo cambia un cursor que ya no se usa al filtrar. | `vault/page.tsx` | parcial |
| C-28 | Sin `prefers-reduced-motion`. | `globals.css` | corregido |
| C-29 | Barra superior sin hora del último evento ni botón de reconexión manual. | `topbar.tsx` | corregido |
| C-30 | `builds` muestra sin separación builds de test y acciones solo-icono sin texto accesible. | `builds/page.tsx` | corregido |
| C-31 | No se mostraba qué billetera estaba en ejecución pese a que el agente ya emite `wallet_detected`/`wallet_closed`. | `victim-detail.tsx` | corregido |

## Notas de datos

- La semilla de `prismlauncher/accounts.json` guarda claves hex; el escáner deriva dirección y balance `0 ETH`. Debe rotularse como fichero de semillas y no contarse como saldo.
- `phantom`/`trust` detectados como extensión deben marcar «wallet abierta» cuando el proceso del navegador con la extensión está activo (`wallet_detected`/`wallet_closed` ya existen en el contrato).
