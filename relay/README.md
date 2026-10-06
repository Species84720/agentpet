# AgentPet Cloud Relay

Cloudflare-first real-time backend for mobile companions. It deliberately does
not share the public pet-catalog Worker: this service contains private agent
activity and must be deployed to a separate hostname, for example
`relay.agentpet.example`.

## Deploy

Use Node.js 22 or newer for Wrangler.

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
| `POST /v1/care-deltas` | agent | Queue an idempotent transcript token increment for Android |
| `POST /v1/approvals` | agent | Create a three-minute approval request for paired companions |
| `GET /v1/approvals` | companion | List pending approval requests |
| `GET /v1/approvals/{id}` | agent | Read an approval decision |
| `POST /v1/approvals/{id}/decision` | paired device | Submit the first allow/deny decision |
| `POST /v1/approvals/{id}/cancel` | paired device | Cancel a request without deciding it |
| `GET /v1/android-care` | companion | Read saved Android care and pending token increments |
| `POST /v1/android-care/consume` | companion | Atomically save care and acknowledge applied increments |
| `GET/PUT /v1/profile` | any device | Read/update shared pet settings (optimistic `version`) |
| `GET /v1/events?before=&limit=` | any device | Paginated history; never used for live updates |
| `DELETE /v1/logs?before=` | any device | Explicitly clear all, or only logs older than epoch-ms `before` |
| `GET /v1/health` | public | Service health |
| `GET/POST/DELETE /v1/pet-brain` | companion | Check AI setup, converse, or clear bounded memories |

The Worker automatically prunes event rows older than `LOG_RETENTION_DAYS`
(30 by default) on ingestion. `DELETE /v1/logs` is intentionally separate from
the pet profile and the current live session snapshot, so a user can reclaim
history storage without resetting their Tamagotchi.

Agent and companion device tokens must be minted with the same `userId`; the
Worker uses that ID to select the Durable Object room. A companion can report
`Inbox reachable` while still looking at a different room if its token was
paired to another `userId`. Approval requests expire after 180 seconds, so
respond promptly.

For live updates, connect `wss://<relay>/v1/live` with the companion token in
the `Authorization: Bearer` handshake header. The first frame is a
`snapshot`, followed by `event`, `care_delta`, `android_care`, `profile`, and
`logs_cleared` frames.

Do not put device tokens in WebSocket query strings: URLs are routinely logged.

## Desktop hook bridge

Create `~/.agentpet/cloud-relay.json` on each desktop that emits events:

```json
{ "url": "https://relay.example.com", "token": "AGENT_DEVICE_TOKEN" }
```

Existing hooks continue to write to the local desktop socket and mirror events
to the relay. Codex and local Copilot CLI permission requests are registered in
the Cloudflare room with the agent token, then race the desktop pet against
Android for up to three minutes; the first allow/deny decision wins. If neither
answers, the hook falls back to the agent's native permission prompt. Copilot
Cloud Agent is non-interactive and cannot use this phone approval flow. The
hook uses `curl` for all
Cloudflare approval calls because the deployed edge rejects Python `urllib`
requests (HTTP 403, error 1010). A relay outage still leaves the desktop pet
and the agent's native permission prompt available.
# Optional Power Automate pet brain

See [`../power-automate/README.md`](../power-automate/README.md) for the flow
generator and prompt. Store the signed flow URL with
`npx wrangler secret put POWER_AUTOMATE_URL`. `GET/POST/DELETE /v1/pet-brain`
requires a companion token. Memory is bounded; failed calls count against the
daily cap. The brain cannot mutate approval decisions or care totals.
