# AgentPet Cloud Relay

Cloudflare-first real-time backend for mobile companions. It deliberately does
not share the public pet-catalog Worker: this service contains private agent
activity and must be deployed to a separate hostname, for example
`relay.agentpet.example`.

## Deploy

1. Create a D1 database and replace `database_id` in `wrangler.jsonc`.
2. Run `wrangler d1 execute agentpet-relay --remote --file=schema.sql`.
3. Set the bootstrap secret once: `wrangler secret put PAIRING_SECRET`.
4. Deploy with `npm install && npm run deploy`.

`PAIRING_SECRET` is used only to mint device tokens. Store each returned token
in Android Keystore or the desktop Keychain/config file; the relay stores only
a SHA-256 hash.

## HTTP protocol

All authenticated endpoints use `Authorization: Bearer <device-token>`.

| Endpoint | Role | Purpose |
| --- | --- | --- |
| `POST /v1/devices` | bootstrap secret | Mint an `agent` or `companion` device token |
| `POST /v1/events` | agent | Persist and broadcast an AgentEvent |
| `GET/PUT /v1/profile` | any device | Read/update shared pet settings (optimistic `version`) |
| `GET /v1/events?before=&limit=` | any device | Paginated history; never used for live updates |
| `DELETE /v1/logs?before=` | any device | Explicitly clear all, or only logs older than epoch-ms `before` |
| `GET /v1/health` | public | Service health |

The Worker automatically prunes event rows older than `LOG_RETENTION_DAYS`
(30 by default) on ingestion. `DELETE /v1/logs` is intentionally separate from
the pet profile and the current live session snapshot, so a user can reclaim
history storage without resetting their Tamagotchi.

For live updates, connect `wss://<relay>/v1/live` with the companion token in
the `Authorization: Bearer` handshake header. The first frame is a
`snapshot`, followed by `event`, `profile`, and `logs_cleared` frames.

Do not put device tokens in WebSocket query strings: URLs are routinely logged.

## Desktop hook bridge

Create `~/.agentpet/cloud-relay.json` on each desktop that emits events:

```json
{ "url": "https://relay.example.com", "token": "AGENT_DEVICE_TOKEN" }
```

Existing hooks continue to write to the local desktop socket first and then make
a bounded, fail-open mirror request to the relay. A cloud outage therefore
never blocks an agent. Approval-gated events intentionally stay local in this
first version; remote approval requires a separate, authenticated blocking
round trip.
