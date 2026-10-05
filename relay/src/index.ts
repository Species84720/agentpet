export interface Env {
  DB: D1Database;
  ROOM: DurableObjectNamespace;
  PAIRING_SECRET: string;
  LOG_RETENTION_DAYS?: string;
}

type Device = { token_hash: string; user_id: string; name: string; role: "agent" | "companion" };
type Event = Record<string, unknown> & { sessionId: string; eventName: string; timestamp?: number };
type Auth = { userId: string; deviceHash: string; role: Device["role"] };
type CareDelta = { id?: string; sessionId: string; agentKind?: string; tokens: number; project?: string; createdAt?: number };
type Approval = { requestId: string; sessionId: string; agentKind: string; toolName: string; summary: string; project?: string; createdAt: number; expiresAt: number; decision?: "allow" | "deny"; resolvedAt?: number; cancelledAt?: number };

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "content-type": "application/json", "cache-control": "no-store" },
});
const textEncoder = new TextEncoder();

async function sha256(value: string): Promise<string> {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", textEncoder.encode(value)));
  return [...bytes].map((x) => x.toString(16).padStart(2, "0")).join("");
}
function token(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return [...bytes].map((x) => x.toString(16).padStart(2, "0")).join("");
}
function bearer(request: Request): string | null {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7) : null;
}
async function authenticate(request: Request, env: Env): Promise<Auth | null> {
  const raw = bearer(request);
  if (!raw) return null;
  const hash = await sha256(raw);
  const device = await env.DB.prepare("SELECT token_hash, user_id, name, role FROM devices WHERE token_hash=?").bind(hash).first<Device>();
  if (!device) return null;
  await env.DB.prepare("UPDATE devices SET last_seen_at=? WHERE token_hash=?").bind(Date.now(), hash).run();
  return { userId: device.user_id, deviceHash: hash, role: device.role };
}
function room(env: Env, userId: string) { return env.ROOM.get(env.ROOM.idFromName(userId)); }
function eventIsValid(value: unknown): value is Event {
  if (!value || typeof value !== "object") return false;
  const e = value as Record<string, unknown>;
  return typeof e.sessionId === "string" && e.sessionId.length > 0 && typeof e.eventName === "string" && e.eventName.length > 0;
}
function careDeltaIsValid(value: unknown): value is CareDelta {
  if (!value || typeof value !== "object") return false;
  const d = value as Record<string, unknown>;
  return typeof d.sessionId === "string" && d.sessionId.length > 0
    && Number.isInteger(d.tokens) && Number(d.tokens) > 0 && Number(d.tokens) <= 100_000_000;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { headers: { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, content-type", "access-control-allow-methods": "GET, POST, PUT, DELETE, OPTIONS" } });
    if (url.pathname === "/v1/health") return json({ ok: true });

    if (url.pathname === "/v1/devices" && request.method === "POST") {
      if (!env.PAIRING_SECRET || bearer(request) !== env.PAIRING_SECRET) return json({ error: "unauthorized" }, 401);
      const body = await request.json<any>().catch(() => null);
      const userId = String(body?.userId || "").trim();
      const name = String(body?.name || "").trim().slice(0, 80);
      const role = body?.role === "agent" ? "agent" : body?.role === "companion" ? "companion" : "";
      if (!userId || !name || !role) return json({ error: "userId, name and role are required" }, 400);
      const raw = token();
      await env.DB.prepare("INSERT INTO devices (token_hash,user_id,name,role,created_at,last_seen_at) VALUES (?,?,?,?,?,?)")
        .bind(await sha256(raw), userId, name, role, Date.now(), Date.now()).run();
      return json({ token: raw, role });
    }

    const auth = await authenticate(request, env);
    if (!auth) return json({ error: "unauthorized" }, 401);

    if (url.pathname === "/v1/live-status" && request.method === "GET") {
      return room(env, auth.userId).fetch("https://room/status");
    }
    if (url.pathname === "/v1/live") {
      if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
        return json({ error: "WebSocket upgrade required" }, 426);
      }
      // Tokens travel in Sec-WebSocket-Protocol, not URLs. Validate it against
      // the already authenticated Authorization header before passing to the DO.
      if (auth.role !== "companion") return json({ error: "companion token required" }, 403);
      // Forward the original upgraded request verbatim. Cloudflare strips a
      // synthetic Upgrade header from a new fetch, but preserves it when the
      // actual client request is handed to the Durable Object.
      return room(env, auth.userId).fetch(request);
    }
    if (url.pathname === "/v1/events" && request.method === "POST") {
      if (auth.role !== "agent") return json({ error: "agent token required" }, 403);
      const event = await request.json<unknown>().catch(() => null);
      if (!eventIsValid(event)) return json({ error: "sessionId and eventName are required" }, 400);
      const now = Date.now();
      const id = crypto.randomUUID();
      const normalized = { ...event, timestamp: typeof event.timestamp === "number" ? event.timestamp : now };
      await env.DB.batch([
        env.DB.prepare("INSERT INTO agent_events (id,user_id,device_hash,session_id,event_json,created_at) VALUES (?,?,?,?,?,?)")
          .bind(id, auth.userId, auth.deviceHash, normalized.sessionId, JSON.stringify(normalized), now),
        env.DB.prepare("DELETE FROM agent_events WHERE user_id=? AND created_at<?")
          .bind(auth.userId, now - Math.max(1, Number(env.LOG_RETENTION_DAYS || 30)) * 86_400_000),
      ]);
      await room(env, auth.userId).fetch("https://room/publish", { method: "POST", body: JSON.stringify({ type: "event", id, event: normalized }) });
      return json({ id, acceptedAt: now }, 202);
    }
    if (url.pathname === "/v1/approvals" && request.method === "POST") {
      if (auth.role !== "agent") return json({ error: "agent token required" }, 403);
      const body = await request.json<any>().catch(() => null);
      if (!body || typeof body.requestId !== "string" || !/^[a-zA-Z0-9_-]{16,100}$/.test(body.requestId)
        || typeof body.sessionId !== "string" || !body.sessionId
        || typeof body.toolName !== "string" || !body.toolName) return json({ error: "requestId, sessionId and toolName are required" }, 400);
      const approval: Approval = {
        requestId: body.requestId, sessionId: body.sessionId.slice(0, 200), agentKind: String(body.agentKind || "codex").slice(0, 32),
        toolName: body.toolName.slice(0, 100), summary: String(body.summary || "").slice(0, 4000),
        project: typeof body.project === "string" ? body.project.slice(0, 500) : undefined,
        createdAt: Date.now(), expiresAt: Date.now() + 60_000,
      };
      return room(env, auth.userId).fetch("https://room/approval/create", { method: "POST", body: JSON.stringify(approval) });
    }
    if (url.pathname === "/v1/approvals" && request.method === "GET") {
      if (auth.role !== "companion") return json({ error: "companion token required" }, 403);
      return room(env, auth.userId).fetch("https://room/approval/list");
    }
    const approvalMatch = url.pathname.match(/^\/v1\/approvals\/([a-zA-Z0-9_-]{16,100})(?:\/(decision|cancel))?$/);
    if (approvalMatch) {
      const requestId = approvalMatch[1];
      if (request.method === "GET") {
        if (auth.role !== "agent") return json({ error: "agent token required" }, 403);
        return room(env, auth.userId).fetch(`https://room/approval/result?id=${encodeURIComponent(requestId)}`);
      }
      if (request.method === "POST" && approvalMatch[2] === "decision") {
        const body = await request.json<any>().catch(() => null);
        if (body?.decision !== "allow" && body?.decision !== "deny") return json({ error: "decision must be allow or deny" }, 400);
        return room(env, auth.userId).fetch(`https://room/approval/decision?id=${encodeURIComponent(requestId)}`, { method: "POST", body: JSON.stringify({ decision: body.decision }) });
      }
      if (request.method === "POST" && approvalMatch[2] === "cancel") {
        return room(env, auth.userId).fetch(`https://room/approval/cancel?id=${encodeURIComponent(requestId)}`, { method: "POST" });
      }
    }
    if (url.pathname === "/v1/care-deltas" && request.method === "POST") {
      if (auth.role !== "agent") return json({ error: "agent token required" }, 403);
      const delta = await request.json<unknown>().catch(() => null);
      if (!careDeltaIsValid(delta)) return json({ error: "sessionId and a positive integer tokens value are required" }, 400);
      const now = Date.now();
      const id = typeof delta.id === "string" && delta.id.length <= 100 ? delta.id : crypto.randomUUID();
      const createdAt = typeof delta.createdAt === "number" ? delta.createdAt : now;
      const result = await env.DB.prepare("INSERT OR IGNORE INTO care_deltas (id,user_id,device_hash,session_id,agent_kind,tokens,project,created_at,consumed_at) VALUES (?,?,?,?,?,?,?,?,NULL)")
        .bind(id, auth.userId, auth.deviceHash, delta.sessionId, String(delta.agentKind || "unknown").slice(0, 32), delta.tokens, delta.project || null, createdAt).run();
      if (result.meta.changes > 0) {
        await room(env, auth.userId).fetch("https://room/publish", { method: "POST", body: JSON.stringify({ type: "care_delta", delta: { id, sessionId: delta.sessionId, tokens: delta.tokens, agentKind: delta.agentKind || "unknown", createdAt } }) });
      }
      return json({ id, acceptedAt: now, duplicate: result.meta.changes === 0 }, 202);
    }
    if (url.pathname === "/v1/android-care" && request.method === "GET") {
      if (auth.role !== "companion") return json({ error: "companion token required" }, 403);
      const [care, pending] = await Promise.all([
        env.DB.prepare("SELECT version,care_json,updated_at FROM android_care WHERE user_id=?").bind(auth.userId).first<any>(),
        env.DB.prepare("SELECT id,session_id,agent_kind,tokens,project,created_at FROM care_deltas WHERE user_id=? AND consumed_at IS NULL ORDER BY created_at ASC LIMIT 500").bind(auth.userId).all<any>(),
      ]);
      return json({
        version: care?.version ?? 0,
        care: care ? JSON.parse(care.care_json) : {},
        updatedAt: care?.updated_at ?? null,
        deltas: (pending.results || []).map((d: any) => ({ id: d.id, sessionId: d.session_id, agentKind: d.agent_kind, tokens: d.tokens, project: d.project, createdAt: d.created_at })),
      });
    }
    if (url.pathname === "/v1/android-care/consume" && request.method === "POST") {
      if (auth.role !== "companion") return json({ error: "companion token required" }, 403);
      const body = await request.json<any>().catch(() => null);
      if (!body || !Number.isInteger(body.version) || !body.care || typeof body.care !== "object" || !Array.isArray(body.deltaIds)) {
        return json({ error: "version, care and deltaIds are required" }, 400);
      }
      const ids = [...new Set(body.deltaIds.filter((id: unknown) => typeof id === "string" && id.length <= 100))].slice(0, 500) as string[];
      const current = await env.DB.prepare("SELECT version FROM android_care WHERE user_id=?").bind(auth.userId).first<any>();
      if ((current?.version ?? 0) !== body.version) return json({ error: "conflict", version: current?.version ?? 0 }, 409);
      const version = body.version + 1, now = Date.now();
      const statements = [env.DB.prepare("INSERT INTO android_care (user_id,version,care_json,updated_at) VALUES (?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET version=excluded.version,care_json=excluded.care_json,updated_at=excluded.updated_at")
        .bind(auth.userId, version, JSON.stringify(body.care), now)];
      if (ids.length) statements.push(env.DB.prepare(`UPDATE care_deltas SET consumed_at=? WHERE user_id=? AND consumed_at IS NULL AND id IN (${ids.map(() => "?").join(",")})`).bind(now, auth.userId, ...ids));
      // D1 batch is transactional: token deltas cannot be acknowledged without
      // the resulting care snapshot being persisted in the same commit.
      await env.DB.batch(statements);
      await room(env, auth.userId).fetch("https://room/publish", { method: "POST", body: JSON.stringify({ type: "android_care", version, updatedAt: now }) });
      return json({ version, updatedAt: now, consumed: ids.length });
    }
    if (url.pathname === "/v1/profile" && request.method === "GET") {
      const row = await env.DB.prepare("SELECT version,profile_json,updated_at FROM pet_profiles WHERE user_id=?").bind(auth.userId).first<any>();
      return json(row ? { version: row.version, profile: JSON.parse(row.profile_json), updatedAt: row.updated_at } : { version: 0, profile: {}, updatedAt: null });
    }
    if (url.pathname === "/v1/profile" && request.method === "PUT") {
      const body = await request.json<any>().catch(() => null);
      if (!body || !Number.isInteger(body.version) || !body.profile || typeof body.profile !== "object") return json({ error: "version and profile are required" }, 400);
      const current = await env.DB.prepare("SELECT version FROM pet_profiles WHERE user_id=?").bind(auth.userId).first<any>();
      if ((current?.version ?? 0) !== body.version) return json({ error: "conflict", version: current?.version ?? 0 }, 409);
      const version = body.version + 1, now = Date.now();
      await env.DB.prepare("INSERT INTO pet_profiles (user_id,version,profile_json,updated_at) VALUES (?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET version=excluded.version,profile_json=excluded.profile_json,updated_at=excluded.updated_at")
        .bind(auth.userId, version, JSON.stringify(body.profile), now).run();
      await room(env, auth.userId).fetch("https://room/publish", { method: "POST", body: JSON.stringify({ type: "profile", version, profile: body.profile, updatedAt: now }) });
      return json({ version, updatedAt: now });
    }
    if (url.pathname === "/v1/events" && request.method === "GET") {
      const limit = Math.min(200, Math.max(1, Number(url.searchParams.get("limit") || 50)));
      const before = Number(url.searchParams.get("before") || Date.now());
      const after = Number(url.searchParams.get("after") || 0);
      if (!Number.isFinite(before) || !Number.isFinite(after)) return json({ error: "before and after must be epoch milliseconds" }, 400);
      const rows = await env.DB.prepare("SELECT id,event_json,created_at FROM agent_events WHERE user_id=? AND created_at>? AND created_at<? ORDER BY created_at DESC LIMIT ?").bind(auth.userId, after, before, limit).all<any>();
      return json({ events: (rows.results || []).map((r: any) => ({ id: r.id, ...JSON.parse(r.event_json), storedAt: r.created_at })) });
    }
    if (url.pathname === "/v1/logs" && request.method === "DELETE") {
      const before = url.searchParams.has("before") ? Number(url.searchParams.get("before")) : Date.now();
      if (!Number.isFinite(before)) return json({ error: "before must be epoch milliseconds" }, 400);
      const result = await env.DB.prepare("DELETE FROM agent_events WHERE user_id=? AND created_at<?").bind(auth.userId, before + 1).run();
      await room(env, auth.userId).fetch("https://room/publish", { method: "POST", body: JSON.stringify({ type: "logs_cleared", before, deleted: result.meta.changes }) });
      return json({ deleted: result.meta.changes, before });
    }
    return json({ error: "not found" }, 404);
  },
};

export class PetRoom implements DurableObject {
  private sessions = new Map<string, Event>();
  private approvals = new Map<string, Approval>();
  private readonly ready: Promise<void>;
  constructor(private ctx: DurableObjectState) {
    // Hibernation reconstructs the object while clients remain connected. Reload
    // the compact snapshot before serving a client so reconnect/live state is
    // not dependent on an in-memory map surviving a Worker eviction.
    this.ready = this.ctx.blockConcurrencyWhile(async () => {
      const saved = await this.ctx.storage.get<[string, Event][]>("sessions");
      this.sessions = new Map(saved || []);
      const approvals = await this.ctx.storage.get<[string, Approval][]>("approvals");
      this.approvals = new Map(approvals || []);
      // Give in-flight approvals created by the earlier no-expiry Worker build
      // the same one-minute lifetime as new requests.
      for (const approval of this.approvals.values()) {
        if (!approval.expiresAt) approval.expiresAt = approval.createdAt + 60_000;
      }
      await this.scheduleApprovalAlarm();
    });
  }
  async fetch(request: Request): Promise<Response> {
    await this.ready;
    const path = new URL(request.url).pathname;
    if (path === "/live" || path === "/v1/live") {
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.ctx.acceptWebSocket(server);
      server.serializeAttachment({ kind: "companion" });
      server.send(JSON.stringify({ type: "connected", connectedAt: Date.now(), companions: this.ctx.getWebSockets().length }));
      server.send(JSON.stringify({ type: "snapshot", sessions: [...this.sessions.values()] }));
      for (const approval of this.approvals.values()) if (!approval.decision && !approval.cancelledAt && approval.expiresAt > Date.now()) server.send(JSON.stringify({ type: "approval_requested", approval }));
      return new Response(null, { status: 101, webSocket: client });
    }
    if (path === "/status") return json({ companions: this.ctx.getWebSockets().length, checkedAt: Date.now() });
    if (path === "/publish" && request.method === "POST") {
      const message = await request.json<any>();
      if (message.type === "event" && eventIsValid(message.event)) {
        this.sessions.set(message.event.sessionId, message.event);
        await this.ctx.storage.put("sessions", [...this.sessions.entries()]);
      }
      this.broadcast(message);
      return new Response(null, { status: 204 });
    }
    if (path === "/approval/create" && request.method === "POST") {
      const approval = await request.json<Approval>();
      const existing = this.approvals.get(approval.requestId);
      if (existing) return json({ ok: true, requestId: existing.requestId, state: existing.decision ? "resolved" : existing.cancelledAt || existing.expiresAt <= Date.now() ? "expired" : "pending", decision: existing.decision });
      this.approvals.set(approval.requestId, approval);
      await this.ctx.storage.put("approvals", [...this.approvals.entries()]);
      await this.scheduleApprovalAlarm();
      this.broadcast({ type: "approval_requested", approval });
      return json({ ok: true, requestId: approval.requestId, state: "pending" }, 202);
    }
    if (path === "/approval/result" && request.method === "GET") {
      const id = new URL(request.url).searchParams.get("id") || "";
      const approval = this.approvals.get(id);
      if (!approval) return json({ error: "approval not found" }, 404);
      if (!approval.decision && !approval.cancelledAt && approval.expiresAt <= Date.now()) {
        approval.cancelledAt = approval.expiresAt;
        await this.ctx.storage.put("approvals", [...this.approvals.entries()]);
        this.broadcast({ type: "approval_resolved", requestId: id, decision: null, cancelled: true, expired: true });
        await this.scheduleApprovalAlarm();
      }
      return json({ requestId: id, state: approval.decision ? "resolved" : approval.cancelledAt ? "expired" : "pending", decision: approval.decision || null });
    }
    if (path === "/approval/list" && request.method === "GET") {
      const now = Date.now();
      const approvals = [...this.approvals.values()].filter(a => !a.decision && !a.cancelledAt && a.expiresAt > now);
      return json({ approvals });
    }
    if (path === "/approval/decision" && request.method === "POST") {
      const id = new URL(request.url).searchParams.get("id") || "";
      const body = await request.json<{ decision?: "allow" | "deny" }>();
      const approval = this.approvals.get(id);
      if (!approval) return json({ error: "approval not found" }, 404);
      const accepted = !approval.decision && !approval.cancelledAt && approval.expiresAt > Date.now();
      if (accepted) {
        approval.decision = body.decision;
        approval.resolvedAt = Date.now();
      } else if (!approval.decision && !approval.cancelledAt && approval.expiresAt <= Date.now()) {
        approval.cancelledAt = approval.expiresAt;
        this.broadcast({ type: "approval_resolved", requestId: id, decision: null, cancelled: true, expired: true });
      }
      await this.ctx.storage.put("approvals", [...this.approvals.entries()]);
      this.broadcast({ type: "approval_resolved", requestId: id, decision: approval.decision });
      await this.scheduleApprovalAlarm();
      return json({ ok: true, accepted, decision: approval.decision, state: approval.decision ? "resolved" : "expired" });
    }
    if (path === "/approval/cancel" && request.method === "POST") {
      const id = new URL(request.url).searchParams.get("id") || "";
      const approval = this.approvals.get(id);
      if (!approval) return json({ error: "approval not found" }, 404);
      const accepted = !approval.decision && !approval.cancelledAt && approval.expiresAt > Date.now();
      if (accepted) approval.cancelledAt = Date.now();
      await this.ctx.storage.put("approvals", [...this.approvals.entries()]);
      if (accepted) this.broadcast({ type: "approval_resolved", requestId: id, decision: null, cancelled: true });
      await this.scheduleApprovalAlarm();
      return json({ ok: true, accepted, state: approval.decision ? "resolved" : approval.cancelledAt ? "expired" : "pending", decision: approval.decision || null });
    }
    return new Response("not found", { status: 404 });
  }
  async alarm(): Promise<void> {
    const now = Date.now();
    for (const [id, approval] of this.approvals) {
      if (!approval.decision && !approval.cancelledAt && approval.expiresAt <= now) {
        approval.cancelledAt = approval.expiresAt;
        this.broadcast({ type: "approval_resolved", requestId: id, decision: null, cancelled: true, expired: true });
      } else if ((approval.decision || approval.cancelledAt) && (approval.resolvedAt || approval.cancelledAt || approval.createdAt) + 60_000 <= now) {
        this.approvals.delete(id);
      }
    }
    await this.ctx.storage.put("approvals", [...this.approvals.entries()]);
    await this.scheduleApprovalAlarm();
  }
  private async scheduleApprovalAlarm(): Promise<void> {
    const next = [...this.approvals.values()]
      .filter(a => !a.decision && !a.cancelledAt)
      .map(a => a.expiresAt)
      .concat([...this.approvals.values()]
        .filter(a => a.decision || a.cancelledAt)
        .map(a => (a.resolvedAt || a.cancelledAt || a.createdAt) + 60_000))
      .sort((a, b) => a - b)[0];
    if (next) await this.ctx.storage.setAlarm(next);
    else await this.ctx.storage.deleteAlarm();
  }
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (message === "snapshot") ws.send(JSON.stringify({ type: "snapshot", sessions: [...this.sessions.values()] }));
  }
  webSocketClose(ws: WebSocket): void { ws.close(1000, "closed"); }
  private broadcast(message: unknown): void {
    const body = JSON.stringify(message);
    for (const socket of this.ctx.getWebSockets()) if (socket.readyState === WebSocket.OPEN) socket.send(body);
  }
}
