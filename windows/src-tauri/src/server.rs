//! A tiny localhost HTTP listener. The `agentpet hook` CLI (run by each agent's
//! hook) POSTs an event here; we map it to a pet state and emit a Tauri event
//! to the UI (broadcast: pet overlay + Settings both listen). Mirrors the macOS
//! app's unix-socket daemon, but cross-platform.
//!
//! Like the macOS AppDaemon it also:
//! - drains events queued on disk while the app was closed (replayed with
//!   their original timestamps so stale sessions prune instead of resurrecting)
//! - on a Claude `Stop`, reads the transcript to see whether Claude actually
//!   ended by ASKING something, and corrects `done` → `waiting` (the final
//!   state is emitted once , no done-then-waiting double notification)
//! - resolves a human conversation title from the transcript

use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::sync::mpsc::Sender;
use std::sync::{Mutex, OnceLock};
use tauri::{AppHandle, Emitter};

pub const HOOK_PORT: u16 = 47628;

// Held approval requests, keyed by request id. A gated hook parks its HTTP
// response until the desktop or phone submits a decision.
fn pending() -> &'static Mutex<HashMap<String, Sender<String>>> {
    static P: OnceLock<Mutex<HashMap<String, Sender<String>>>> = OnceLock::new();
    P.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Frontend → daemon: deliver the user's decision to the parked hook request.
pub fn resolve_approval(_app: &AppHandle, id: &str, decision: &str) {
    let decision = if id.starts_with("codex-") {
        cloud_approval_decision(id, decision).unwrap_or_else(|| decision.to_string())
    } else { decision.to_string() };
    if let Ok(mut map) = pending().lock() {
        if let Some(tx) = map.remove(id) {
            let _ = tx.send(decision.clone());
        }
    }
}

/// End the desktop copy of a Codex approval when its hook hands control back to
/// Codex's native permission prompt. This is a cancellation, never a denial.
fn cancel_approval(id: &str) {
    if let Ok(mut map) = pending().lock() {
        map.remove(id);
    }
}

fn cloud_approval_decision(id: &str, decision: &str) -> Option<String> {
    let home = dirs::home_dir()?;
    let config: Value = serde_json::from_slice(&std::fs::read(home.join(".agentpet/cloud-relay.json")).ok()?).ok()?;
    let base = config.get("url")?.as_str()?.trim_end_matches('/');
    let token = config.get("token")?.as_str()?;
    if !base.starts_with("https://") || token.is_empty() { return None; }
    let endpoint = format!("{base}/v1/approvals/{id}/decision");
    let body = serde_json::json!({ "decision": decision }).to_string();
    let auth = format!("Authorization: Bearer {token}");
    let output = std::process::Command::new("curl").args([
        "--silent", "--show-error", "--max-time", "2", "--request", "POST", &endpoint,
        "--header", &auth, "--header", "Content-Type: application/json", "--data-binary", &body,
    ]).output().ok()?;
    if !output.status.success() { return None; }
    let result: Value = serde_json::from_slice(&output.stdout).ok()?;
    result.get("decision")?.as_str().map(str::to_owned)
}

/// Opt-in whitelist: the gate stays OFF unless `~/.agentpet/approval-gate.json`
/// exists with `{"tools":["Bash",...]}`. Same config path as the macOS app.
pub fn gated_tools() -> HashSet<String> {
    let Some(home) = dirs::home_dir() else { return HashSet::new() };
    let path = home.join(".agentpet").join("approval-gate.json");
    let Ok(data) = std::fs::read_to_string(path) else { return HashSet::new() };
    let Ok(v) = serde_json::from_str::<Value>(&data) else { return HashSet::new() };
    v.get("tools")
        .and_then(|t| t.as_array())
        .map(|arr| arr.iter().filter_map(|x| x.as_str().map(String::from)).collect())
        .unwrap_or_default()
}

fn is_gated(body: &str) -> bool {
    let Ok(v) = serde_json::from_str::<Value>(body) else { return false };
    if str_of(&v, "agent") == "codex" && str_of(&v, "event") == "PermissionRequest" {
        return v.get("approvalRequestId").and_then(Value::as_str).is_some_and(|id| !id.is_empty());
    }
    if str_of(&v, "agent") != "claude" || str_of(&v, "event") != "PreToolUse" {
        return false;
    }
    let tool = str_of(&v, "tool");
    !tool.is_empty() && gated_tools().contains(tool)
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Parks the hook's HTTP response while the pet bubble shows Allow/Deny. Runs on
/// its own thread so the request loop is never blocked. Falls back to "ask" (the
/// agent's normal prompt) on the 10 s timeout , never a silent allow/deny.
fn handle_approval(app: AppHandle, body: String, req: tiny_http::Request) {
    let v: Value = serde_json::from_str(&body).unwrap_or(Value::Null);
    let session = str_of(&v, "session").to_string();
    let tool = str_of(&v, "tool").to_string();
    let summary: String = {
        let s = [str_of(&v, "desc"), str_of(&v, "file"), &tool]
            .into_iter()
            .find(|s| !s.is_empty())
            .unwrap_or("");
        s.chars().take(4000).collect()
    };
    let execution: String = v.get("execution").and_then(Value::as_str).unwrap_or("")
        .chars().take(8000).collect();
    let id = v.get("approvalRequestId").and_then(Value::as_str).map(str::to_owned)
        .unwrap_or_else(|| format!("{}-{}", session, now_millis()));

    let (tx, rx) = std::sync::mpsc::channel();
    if let Ok(mut map) = pending().lock() {
        map.insert(id.clone(), tx);
    }

    // Let the session surface as working, then ask the bubble for a decision.
    handle_event(&app, &body);
    let _ = app.emit(
        "agent-approval",
        serde_json::json!({ "id": id, "session": session, "tool": tool, "summary": summary, "execution": execution }),
    );

    // Codex gets three minutes for a desktop-pet or phone answer.
    // On timeout, "ask" tells the hook to return no decision so Codex shows its
    // own local permission prompt. Other configured gates remain user-driven.
    let decision = if id.starts_with("codex-") {
        rx.recv_timeout(std::time::Duration::from_secs(180))
            .unwrap_or_else(|_| "ask".to_string())
    } else {
        rx.recv().unwrap_or_else(|_| "ask".to_string())
    };
    if let Ok(mut map) = pending().lock() {
        map.remove(&id);
    }
    let _ = app.emit(
        "agent-approval-resolved",
        serde_json::json!({ "id": id, "session": session }),
    );
    let _ = req.respond(tiny_http::Response::from_string(decision));
}

pub fn start(app: AppHandle) {
    // Replay events queued while the app was closed (name order = time order).
    if let Some(dir) = crate::cli::queue_dir() {
        if let Ok(entries) = std::fs::read_dir(&dir) {
            let mut names: Vec<_> = entries.flatten().map(|e| e.path()).collect();
            names.sort();
            for path in names {
                if let Ok(body) = std::fs::read_to_string(&path) {
                    for line in body.lines().filter(|l| !l.trim().is_empty()) {
                        handle_event(&app, line);
                    }
                }
                let _ = std::fs::remove_file(&path);
            }
        }
    }

    std::thread::spawn(move || {
        let server = match tiny_http::Server::http(("127.0.0.1", HOOK_PORT)) {
            Ok(s) => s,
            Err(_) => return, // another instance owns the port
        };
        for mut req in server.incoming_requests() {
            let mut body = String::new();
            let _ = req.as_reader().read_to_string(&mut body);
            if req.url() == "/resolve" && req.method().as_str() == "POST" {
                let value: Value = serde_json::from_str(&body).unwrap_or(Value::Null);
                let id = str_of(&value, "id").to_string();
                let decision = str_of(&value, "decision").to_string();
                if id.starts_with("codex-") && (decision == "allow" || decision == "deny") {
                    resolve_approval(&app, &id, &decision);
                    let _ = req.respond(tiny_http::Response::from_string("ok"));
                } else {
                    let _ = req.respond(tiny_http::Response::from_string("invalid approval").with_status_code(400));
                }
                continue;
            }
            if req.url() == "/cancel" && req.method().as_str() == "POST" {
                let value: Value = serde_json::from_str(&body).unwrap_or(Value::Null);
                let id = str_of(&value, "id");
                if id.starts_with("codex-") {
                    cancel_approval(id);
                    let _ = req.respond(tiny_http::Response::from_string("ok"));
                } else {
                    let _ = req.respond(tiny_http::Response::from_string("invalid approval").with_status_code(400));
                }
                continue;
            }
            // A gated PreToolUse blocks on the user's decision; hand it to a
            // dedicated thread (which owns `req` and responds later) so the
            // request loop keeps serving other events.
            if is_gated(&body) {
                let app = app.clone();
                std::thread::spawn(move || handle_approval(app, body, req));
                continue;
            }
            handle_event(&app, &body);
            let _ = req.respond(tiny_http::Response::from_string("ok"));
        }
    });
}

fn str_of<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(|x| x.as_str()).unwrap_or("")
}

fn handle_event(app: &AppHandle, body: &str) {
    let Ok(v) = serde_json::from_str::<Value>(body) else { return };
    let agent = str_of(&v, "agent").to_string();
    let event = str_of(&v, "event").to_string();
    let session = str_of(&v, "session").to_string();
    let project = str_of(&v, "project").to_string();
    let message = str_of(&v, "message").to_string();
    let tool = str_of(&v, "tool").to_string();
    let file = str_of(&v, "file").to_string();
    let desc = str_of(&v, "desc").to_string();
    let transcript = str_of(&v, "transcript").to_string();
    let terminal_program = str_of(&v, "terminalProgram").to_string();
    let terminal_focus_url = str_of(&v, "terminalFocusUrl").to_string();
    let ts = v.get("ts").and_then(|x| x.as_u64()).unwrap_or(0);

    if crate::statemap::is_session_end(&agent, &event) {
        let _ = app.emit("agent-end", session);
        return;
    }

    // SubagentStop (Claude/Droid) is not a state change, but the subagent burned
    // tokens in its own transcript , feed those before the event is dropped.
    if event == "SubagentStop" && (agent == "claude" || agent == "droid") {
        let subagent = str_of(&v, "subagent").to_string();
        let parent = if !transcript.is_empty() {
            Some(transcript.clone())
        } else if !project.is_empty() && !session.is_empty() {
            crate::transcript::inferred_path(&session, &project)
        } else {
            None
        };
        if let (false, Some(parent)) = (subagent.is_empty(), parent) {
            let app2 = app.clone();
            let sess = session.clone();
            let proj = project.clone();
            let agent2 = agent.clone();
            std::thread::spawn(move || {
                let path = crate::transcript::subagent_transcript_path(&parent, &subagent);
                if let Some((tokens, cost)) = crate::transcript::new_usage_delta(&path) {
                    if tokens > 0 {
                        let _ = app2.emit("agent-tokens", serde_json::json!({
                            "agent": agent2, "session": sess, "project": proj, "tokens": tokens, "cost": cost,
                        }));
                    }
                }
            });
        }
    }

    let Some(state) = crate::statemap::state(&agent, &event) else { return };

    // Claude's Stop fires identically whether the agent is truly done or just
    // asked the user a question. Resolve the transcript first (fast, local
    // file) and emit exactly one final-state event , like the macOS app.
    let claude_path = if agent == "claude" {
        if !transcript.is_empty() {
            Some(transcript.clone())
        } else if !project.is_empty() && !session.is_empty() {
            crate::transcript::inferred_path(&session, &project)
        } else {
            None
        }
    } else {
        None
    };
    let is_stop_done = event == "Stop" && state == "done";
    // Kept for the token events, since `emit_payload` moves session/project/agent.
    let agent_kind = agent.clone();
    let tok_session = session.clone();
    let tok_project = project.clone();

    let emit_payload = move |app: &AppHandle, state: &str, title: Option<String>| {
        let payload = serde_json::json!({
            "agent": agent, "state": state, "session": session, "project": project,
            "message": message, "tool": tool, "file": file, "desc": desc,
            "event": event, "title": title, "ts": ts,
            "terminalProgram": terminal_program, "terminalFocusUrl": terminal_focus_url,
        });
        let _ = app.emit("agent-event", payload);
    };

    if let Some(path) = claude_path {
        let app = app.clone();
        let state = state.to_string();
        std::thread::spawn(move || {
            let title = crate::transcript::title(&path);
            let final_state = if is_stop_done
                && crate::transcript::latest_assistant_text(&path)
                    .map(|t| crate::transcript::looks_like_question(&t))
                    .unwrap_or(false)
            {
                "waiting".to_string()
            } else {
                state
            };
            emit_payload(&app, &final_state, title);
            // Feed the pet: the tokens Claude burned since the last read (delta),
            // plus their estimated USD cost.
            if let Some((tokens, cost)) = crate::transcript::new_usage_delta(&path) {
                if tokens > 0 {
                    let _ = app.emit("agent-tokens", serde_json::json!({
                        "agent": "claude", "session": tok_session,
                        "project": tok_project, "tokens": tokens, "cost": cost,
                    }));
                }
            }
        });
        return;
    }

    // Codex has no Claude-style transcript; read its rollout JSONL for the tokens
    // it burned, so Codex pets grow like Claude (#29). Path is resolved per event
    // (cached by the offset map inside the reader).
    if agent_kind == "codex" {
        let app2 = app.clone();
        let sess = tok_session.clone();
        let proj = tok_project.clone();
        std::thread::spawn(move || {
            let cwd = if proj.is_empty() { None } else { Some(proj.as_str()) };
            if let Some(path) = crate::transcript::codex_rollout_path_cached(&sess, cwd) {
                if let Some(tokens) = crate::transcript::new_codex_usage_tokens(&path) {
                    if tokens > 0 {
                        let _ = app2.emit("agent-tokens", serde_json::json!({
                            "agent": "codex", "session": sess, "project": proj, "tokens": tokens,
                        }));
                    }
                }
            }
        });
    }

    emit_payload(app, state, None);
}
