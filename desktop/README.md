# SPC Console · escritorio

Contenedor Tauri 2 para el panel. Carga la URL remota configurada y conserva la
web tal cual; no duplica la UI ni el contrato.

## Requisitos

- Rust estable (`rustup`) y Node 20+.
- Linux: `webkit2gtk4.1-devel`, `gtk3-devel`, `librsvg2-devel`, `dbus-devel`,
  `pkgconf-pkg-config`, `openssl-devel` (Fedora) o sus equivalentes Debian.
- Windows: WebView2 (el instalador lo descarga si falta) y MSVC Build Tools.

## Uso

```sh
npm ci
npm run dev
```

La primera vez se abre la ventana de servidor. Valor por defecto:
`https://c2.spc-cloud.net`. Solo se acepta HTTPS; `http://localhost` sirve para
desarrollo local (`npm run dev` en `console/`).

`Archivo → Cambiar servidor…` reabre la configuración. `Ver → Recargar` recarga
la página. El cierre de ventana la oculta en la bandeja; `Salir` termina.

## Empaquetado

```sh
npm run build:linux
npm run build:windows
```

Firma y auto-update no están configurados todavía. El instalador no incluye
Node, PostgreSQL ni el agente: la app es un cliente del panel existente.

## Seguridad

- `contextIsolation` por defecto; no hay `nodeIntegration`.
- La página remota solo puede invocar los comandos expuestos en Rust y los
  permisos listados en `src-tauri/capabilities/default.json`.
- El puente JS (`console/src/lib/desktop.ts`) es no-op en navegador.
- La URL se valida antes de guardar y el host remoto queda limitado por
  `capabilities.remote.urls`.
