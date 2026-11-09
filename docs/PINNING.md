# Certificate pinning del C2 (E2)

Estado: **implementado en el agente**. Activación por config de build
(`tls_pins[]`), vacío por defecto para no romper builds existentes.

## Diseño

- Pin SPKI SHA-256 (base64) **doble**: actual y siguiente. La rotación de
  Let's Encrypt no corta mientras el siguiente esté vigente.
- `X509ExtendedTrustManager` propio que primero delega en el trust store del
  sistema (cadena + hostname) y **después** exige que algún certificado de la
  cadena tenga un pin aceptado. Un pin no sustituye la validación de cadena.
- Se aplica a `HttpsURLConnection` (REST) y al `HttpClient` del WebSocket.
- La actualización (`update.json` en GitHub) **no** usa estos pins: su ancla es
  la firma RSA-3072 separada. No mezclar el pin del C2 con el host de GitHub.
- Pin ausente → `spki_pin_mismatch`; el agente no cae a “confiar en todo”.
- Solo una respuesta firmada por la clave de update puede introducir pins
  nuevos; el panel calcula el pin del certificado en `services/build.ts`.

## Cloudflare y proxy

Con Cloudflare delante de Caddy se pinnea el **intermedio del CDN**, no la hoja:
el CDN rota la hoja sin avisar. El pin de origen solo aplica cuando no hay
proxy. Activar el proxy es un cambio operativo documentado y exige reemitir los
pins en el mismo rollout; no se mezclan modo directo y proxy en la misma flota.

## Riesgos

- Un pin caducado deja fuera a toda la flota. Ventana de rotación de 30 días,
  dos pins y verificación en CI con certificado de staging.
- El pin de CDN no protege de un CDN comprometido; protege de una CA local o de
  un MITM con CA confiada.

## Aceptación

1. MITM con CA no confiada falla.
2. Pin correcto funciona y el handshake conserva hostname.
3. Rotación con el pin siguiente no corta.
4. Activar proxy no deja huérfanos: se actualizan ambos pins a la vez.

Tests: `CertificatePinsTest` valida cadena con un trust store dedicado, acepta el
pin correcto y rechaza otro pin con `spki_pin_mismatch`. Falta el MITM con
proxy real.
