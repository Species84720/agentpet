-- Apply with: wrangler d1 execute agentpet-relay --file=relay/schema.sql
CREATE TABLE IF NOT EXISTS devices (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  role TEXT NOT NULL CHECK(role IN ('agent', 'companion')),
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

CREATE TABLE IF NOT EXISTS pet_profiles (
  user_id TEXT PRIMARY KEY,
  version INTEGER NOT NULL DEFAULT 0,
  profile_json TEXT NOT NULL DEFAULT '{}',
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_events (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  device_hash TEXT NOT NULL,
  session_id TEXT NOT NULL,
  event_json TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_user_time ON agent_events(user_id, created_at DESC);

-- Durable token increments produced by desktop transcript readers. Rows stay
-- pending while Android is offline and are acknowledged only with a saved
-- Android care snapshot.
CREATE TABLE IF NOT EXISTS care_deltas (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  device_hash TEXT NOT NULL,
  session_id TEXT NOT NULL,
  agent_kind TEXT NOT NULL,
  tokens INTEGER NOT NULL CHECK(tokens > 0),
  project TEXT,
  created_at INTEGER NOT NULL,
  consumed_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_care_deltas_pending ON care_deltas(user_id, consumed_at, created_at);

CREATE TABLE IF NOT EXISTS android_care (
  user_id TEXT PRIMARY KEY,
  version INTEGER NOT NULL DEFAULT 0,
  care_json TEXT NOT NULL DEFAULT '{}',
  updated_at INTEGER NOT NULL
);
