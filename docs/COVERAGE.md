# Coverage matrix

Status legend: **D** discovered + indexed, **X** decrypted, **C** copy-only, **U**
unsupported format, **N** not applicable.

Fidelity notes are per extension family, not per version. Browser extension
storage is LevelDB; the module copies it to a temp dir before reading because the
browser keeps `LOCK`.

| Target | Family | Store version | Crypto | Windows path | Status | Reason / decrypt |
|---|---|---|---|---|---|---|
| MetaMask | Chrome/Edge/Brave/Opera | vault v3 (5.x–12.x) | PBKDF2-SHA256 10k + AES-256-GCM | `%LOCALAPPDATA%/<vendor>/User Data/<profile>/Local Extension Settings/nkbihfbeogaeaoehlefnkodbefgpgknn` | D X | password from operation or `crack_vault`; salt+iterations read from `keyMetadata` |
| Phantom | Chromium | vault v3 | PBKDF2-SHA256 10k + AES-256-GCM | `.../bfnaelmomeimhlpmgjnjophhpkkoljpa` | D X | same v3 handler |
| Rabby | Chromium | vault v3 | PBKDF2-SHA256 10k + AES-256-GCM | `.../acmacodkjbdgmoleebolmdjonilkdbch` | D X | same v3 handler |
| Keplr | Chromium | IndexedDB/LevelDB | wallet-specific | `.../IndexedDB/chrome-extension_dmkamcknogkgcdfhhbddcghachkejeap_0.indexeddb.leveldb` | D C | IndexedDB blob captured, no offline decrypt |
| Coinbase Wallet | Chromium | LevelDB | wallet-specific | `.../hnfanknocfeofbddgcijnmhnfnkdnaad` | D C | captured, no offline decrypt |
| Solflare | Chromium | LevelDB/IndexedDB | wallet-specific | `.../bhhhlbepdkbapadjdnnojkbgioiodbic` | D C | captured |
| TronLink | Chromium | LevelDB | wallet-specific | `.../ibnejdfjmmkpcnlpebklmnkoeoihofec` | D C | captured |
| Trust (extension) | Chromium | LevelDB | wallet-specific | `.../egjidjbpglichdcondbcbdnbeeppgdph` | D C | captured |
| Binance Chain | Chromium | LevelDB | wallet-specific | `.../fhbohimaelbohpjbbldcngcnapndodjp` | D C | captured |
| Yoroi | Chromium | IndexedDB | wallet-specific | `.../ffnbelfdoeiohenkjibnmadjiehjhajb` | D C | captured |
| Nami | Chromium | IndexedDB | wallet-specific | `.../lpfcbjknijpeeillifnkikgncikgfhdo` | D C | captured |
| XDEFI | Chromium | IndexedDB | wallet-specific | `.../hmeobnfnfcmdkdcmlblgagmfpfboieaf` | D C | captured |
| Backpack | Chromium | IndexedDB | wallet-specific | `.../aflkmfhebedbjioipglgcbcmnbpgliof` | D C | captured |
| All above, sync store | Chromium | per-extension | as above | `<profile>/Sync Extension Settings/<id>` | D | included when present |
| Exodus | Electron | `seed.seco` scrypt + passphrase | scrypt/AES | `%APPDATA%\Exodus\exodus.wallet` | D C | passphrase unwrapped via machine DPAPI when present; seed remains scrypt-locked |
| Electrum / Electrum-LTC | Python | JSON keystore | Electrum stretch + AES-CBC | `%APPDATA%\Electrum\wallets`, `%APPDATA%\Electrum-LTC\wallets` | D X | `crack_vault` against `*.wallet`; plaintext seed/xprv lifted |
| Atomic | Electron | LevelDB | wallet-specific | `%APPDATA%\Atomic\Local Storage\leveldb` | D C | captured |
| Trust desktop | Electron | LevelDB | wallet-specific | `%APPDATA%\Trust Wallet\Local Storage\leveldb` | D C | captured |
| Coinomi | Native | wallet files | wallet-specific | `%APPDATA%\Coinomi\Coinomi\wallets` | D C | captured |
| Ledger Live | Electron | encrypted native | wallet-specific | `%APPDATA%\Ledger Live` | D C | captured |
| Bitcoin Core | Berkeley DB | `wallet.dat` | AES + Berkeley | `%APPDATA%\Bitcoin\wallets` | D C | captured, no BDB parser in stdlib |
| Monero | Native | wallet keys | wallet-specific | `%APPDATA%\Monero\wallets` | D C | captured |
| Geth / Parity | Web3 keystore | UTC JSON | scrypt/PBKDF2 | `%APPDATA%\Ethereum\keystore`, `%APPDATA%\Parity\Ethereum\keys` | D C | JSON copied; password crack is v3-only |
| Seed files | plaintext | txt/json/rtf/pdf | none | Desktop, Documents, Downloads, `%APPDATA%` | D | 12/18/24 BIP39 with checksum, hex, WIF, xprv, keystore JSON |
| Chromium cookies | Chromium 80+ | SQLite | AES-256-GCM v10 / app-bound v20 | `<profile>/Cookies`, `Network/Cookies` | D X | DPAPI unwrap; ABE does machine→user unwrap |
| Chromium logins | Chromium | SQLite | same as cookies | `<profile>/Login Data` | D X | passwords decrypted |
| Firefox cookies/logins | NSS | SQLite + `key4.db` | PBES2/AES-256-CBC | `~/.mozilla/firefox/<profile>` | D X | empty primary password; primary password reported as locked |
| Trading sessions | web | cookies | as above | Binance, Coinbase, Bybit, OKX, KuCoin hosts | D X | filter by host in `credentials.json` |

## Not implemented in pure Java (alternatives)

| Item | Why | Alternative |
|---|---|---|
| Berkeley DB `wallet.dat` | no BDB reader in stdlib | copy and parse with `db_dump` offline; or bundle a 60 KB BDB reader |
| Exodus `seed.seco` | scrypt params live in native code | passphrase + `exodus_secret.py` offline; already shipped in repo |
| Binance/OKX desktop caches | Chromium cache + encrypted IndexedDB | capture only; replay in a restored profile |
| Firefox primary password | key4 wrapped by user password | `crack_vault` cannot take Firefox yet; copy `key4.db` + logins.json and run `firefox_decrypt` offline |
| macOS Keychain prompt | `security` may show a GUI prompt | first dump after login; cached per profile |
| Linux libsecret | needs a session bus secret agent | falls back to `peanuts`; result marked `mode=peanuts` |

## Known false negatives

- Extension on a non-default profile that is not `Default`/`Profile N` (custom dir name).
- Chrome 127+ ABE where the browser has not run since login; the app-bound key
  cannot be unwrapped without the user DPAPI context (reported `app_bound_*_failed`).
- Wallets whose LevelDB compaction moved the vault into an `.ldb` over 4 MiB;
  `crack_vault` skips files above that bound.
- Seeds in images or PDFs with embedded fonts; only the byte stream is searched.
