#!/usr/bin/env python3
"""Cloudflare activity mirror and optional Codex mobile approval bridge."""

import json
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import queue
import secrets
import urllib.error
import urllib.request

try:
    import fcntl
except ImportError:  # pragma: no cover - Windows fallback
    fcntl = None


CONFIG = os.environ.get("AGENTPET_RELAY_CONFIG", os.path.expanduser("~/.agentpet/cloud-relay.json"))
TIMEOUT_SECONDS = 0.7
STATE = os.environ.get("AGENTPET_RELAY_USAGE_STATE", os.path.expanduser("~/.agentpet/cloud-relay-codex-usage.json"))
LOCK = STATE + ".lock"
APPROVAL_TIMEOUT_SECONDS = 60
LOCAL_HOOK_URL = "http://127.0.0.1:47628"


def post(curl: str, url: str, token: str, body: dict) -> bool:
    data = json.dumps({key: value for key, value in body.items() if value is not None}).encode("utf-8")
    try:
        result = subprocess.run(
            [curl, "--fail", "--silent", "--show-error", "--max-time", str(TIMEOUT_SECONDS),
             "--request", "POST", url,
             "--header", "Authorization: Bearer " + token,
             "--header", "Content-Type: application/json", "--data-binary", "@-"],
            input=data, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=TIMEOUT_SECONDS + 0.1, check=False,
        )
        return result.returncode == 0
    except subprocess.SubprocessError:
        return False


def relay_json(url: str, token: str, body: dict | None = None, timeout: float = 2,
               report_errors: bool = False) -> dict | None:
    """Call the public relay through curl, matching the working event uploader.

    urllib's default User-Agent is rejected by the deployed Cloudflare edge
    (HTTP 403 / error 1010), which made approvals silently stay local while
    event mirroring continued to work.
    """
    curl = shutil.which("curl")
    if not curl:
        if report_errors:
            print("AgentPet: curl is unavailable; Cloudflare approvals cannot be relayed.", file=sys.stderr, flush=True)
        return None
    data = json.dumps(body).encode("utf-8") if body is not None else None
    args = [curl, "--silent", "--show-error", "--max-time", str(timeout),
            "--write-out", "\nAGENTPET_HTTP_STATUS:%{http_code}",
            "--header", "Authorization: Bearer " + token]
    if data is not None:
        args.extend(["--request", "POST", "--header", "Content-Type: application/json",
                     "--data-binary", "@-"])
    args.append(url)
    try:
        response = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  timeout=timeout + 0.5, check=False)
        output = response.stdout.decode("utf-8") if isinstance(response.stdout, bytes) else response.stdout
        payload, marker, status_text = output.rpartition("\nAGENTPET_HTTP_STATUS:")
        if response.returncode != 0 or not marker or not status_text.isdigit():
            if report_errors:
                print("AgentPet: Cloudflare approval request failed at the network layer.", file=sys.stderr, flush=True)
            return None
        if not 200 <= int(status_text) < 300:
            if report_errors:
                try:
                    detail = json.loads(payload).get("error", "")
                except (AttributeError, ValueError):
                    detail = ""
                suffix = f": {detail}" if detail else ""
                print(f"AgentPet: Cloudflare approval request returned HTTP {status_text}{suffix}.",
                      file=sys.stderr, flush=True)
            return None
        return json.loads(payload)
    except (OSError, ValueError, subprocess.SubprocessError):
        if report_errors:
            print("AgentPet: Cloudflare approval request could not be completed.", file=sys.stderr, flush=True)
        return None


def local_request(body: dict, result: queue.Queue) -> None:
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(LOCAL_HOOK_URL + "/event", data=data, method="POST",
                                     headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=APPROVAL_TIMEOUT_SECONDS + 5) as response:
            decision = response.read().decode("utf-8").strip()
            if decision in ("allow", "deny", "ask"):
                result.put(decision)
    except (OSError, urllib.error.URLError):
        pass


def local_resolve(request_id: str, decision: str) -> None:
    data = json.dumps({"id": request_id, "decision": decision}).encode("utf-8")
    request = urllib.request.Request(LOCAL_HOOK_URL + "/resolve", data=data, method="POST",
                                     headers={"Content-Type": "application/json"})
    try:
        urllib.request.urlopen(request, timeout=2).close()
    except (OSError, urllib.error.URLError):
        pass


def local_cancel(request_id: str) -> None:
    data = json.dumps({"id": request_id}).encode("utf-8")
    request = urllib.request.Request(LOCAL_HOOK_URL + "/cancel", data=data, method="POST",
                                     headers={"Content-Type": "application/json"})
    try:
        urllib.request.urlopen(request, timeout=2).close()
    except (OSError, urllib.error.URLError):
        pass


def ensure_relay_approval(base_url: str, token: str, approval: dict) -> None:
    while True:
        result = relay_json(base_url + "/v1/approvals", token, approval, timeout=3, report_errors=True)
        if result and result.get("requestId") == approval["requestId"] \
                and result.get("state") in ("pending", "resolved", "expired"):
            return
        print("AgentPet: approval relay unavailable; request remains pending and will retry.", file=sys.stderr, flush=True)
        time.sleep(5)


def codex_permission_hook(payload: dict, raw_payload: bytes, base_url: str, token: str) -> None:
    """Race the local AgentPet bubble against Android; first decision wins."""
    session_id = payload.get("session_id") or payload.get("conversation_id") or ""
    tool_name = payload.get("tool_name") or "Action"
    tool_input = payload.get("tool_input") if isinstance(payload.get("tool_input"), dict) else {}
    summary = tool_input.get("description") or tool_input.get("command")
    if not isinstance(summary, str) or not summary:
        summary = json.dumps(tool_input, ensure_ascii=False, separators=(",", ":"))
    # Distinct permission requests can have byte-identical payloads, so avoid
    # reusing a short-lived Durable Object approval ID for a later session.
    request_id = "codex-" + hashlib.sha256(raw_payload).hexdigest()[:32] + "-" + secrets.token_hex(8)
    project = payload.get("cwd") if isinstance(payload.get("cwd"), str) else ""
    approval = {"requestId": request_id, "sessionId": session_id, "agentKind": "Codex",
                "toolName": str(tool_name)[:100], "summary": summary[:4000], "project": project[:500]}

    # Approval details live only in the Durable Object's private state;
    # never append commands or approval payloads to the D1 activity log.
    if base_url and token:
        # Register asynchronously so a relay outage never suppresses the local
        # desktop prompt. Retry to keep the phone in sync when available.
        threading.Thread(target=ensure_relay_approval, args=(base_url, token, approval), daemon=True).start()
    local_event = {"agent": "codex", "event": "PermissionRequest", "session": session_id,
                   "project": project, "message": "Approval required", "tool": str(tool_name),
                   "desc": summary[:4000], "approvalRequestId": request_id}
    local_result: queue.Queue = queue.Queue(maxsize=1)
    threading.Thread(target=local_request, args=(local_event, local_result), daemon=True).start()

    deadline = time.monotonic() + APPROVAL_TIMEOUT_SECONDS
    decision = None
    while decision not in ("allow", "deny") and time.monotonic() < deadline:
        try:
            local_decision = local_result.get(timeout=min(0.5, max(0.0, deadline - time.monotonic())))
            if local_decision in ("allow", "deny"):
                decision = local_decision
            elif local_decision == "ask":
                break
        except queue.Empty:
            result = relay_json(base_url + "/v1/approvals/" + request_id, token, timeout=1.5) if base_url and token else None
            if result and result.get("decision") in ("allow", "deny"):
                decision = result["decision"]
                local_resolve(request_id, decision)
                break
        if decision:
            break

    if decision not in ("allow", "deny"):
        # End the mirrored prompts without making a decision. Empty hook output
        # deliberately hands the still-pending request back to Codex's UI.
        if base_url and token:
            relay_json(base_url + "/v1/approvals/" + request_id + "/cancel", token,
                       {"reason": "local_permission_fallback"}, timeout=2)
        local_cancel(request_id)
        return

    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PermissionRequest",
                                                "decision": {"behavior": decision}}}))


def find_rollout(session_id: str, supplied_path: object) -> str | None:
    if isinstance(supplied_path, str) and os.path.isfile(supplied_path):
        return supplied_path
    root = os.path.expanduser("~/.codex/sessions")
    if not os.path.isdir(root):
        return None
    for directory, _, files in os.walk(root):
        for name in files:
            if name.endswith(".jsonl") and session_id in name:
                return os.path.join(directory, name)
    return None


def new_usage_delta(path: str, session_id: str, curl: str, base_url: str, token: str,
                    agent: str, project: object) -> None:
    """Read only newly appended Codex usage; advance the durable cursor after relay ack."""
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    with open(LOCK, "a", encoding="utf-8") as lock_file:
        if fcntl:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        try:
            try:
                with open(STATE, encoding="utf-8") as state_file:
                    state = json.load(state_file)
            except (OSError, ValueError):
                state = {}
            key = os.path.abspath(path)
            start = int(state.get(key, 0))
            size = os.path.getsize(path)
            if start > size:
                start = 0
            with open(path, "rb") as transcript:
                transcript.seek(start)
                raw = transcript.read()
            newline = raw.rfind(b"\n")
            if newline < 0:
                return
            complete = raw[:newline + 1]
            end = start + len(complete)
            tokens = 0
            for line in complete.splitlines():
                if b'"last_token_usage"' not in line:
                    continue
                try:
                    payload = json.loads(line).get("payload", {})
                    usage = payload.get("info", {}).get("last_token_usage", {})
                    input_tokens = int(usage.get("input_tokens", 0))
                    cached_tokens = int(usage.get("cached_input_tokens", 0))
                    output_tokens = int(usage.get("output_tokens", 0))
                    tokens += max(0, input_tokens - cached_tokens) + output_tokens
                except (ValueError, TypeError, AttributeError):
                    continue
            if tokens > 0:
                delta_id = "codex-" + hashlib.sha256(f"{key}:{start}:{end}".encode()).hexdigest()
                delta = {"id": delta_id, "sessionId": session_id, "agentKind": agent,
                         "tokens": tokens, "createdAt": int(time.time() * 1000)}
                if isinstance(project, str) and project:
                    delta["project"] = project
                # If delivery is uncertain, leave the cursor untouched and retry
                # the same id next hook; the Worker deduplicates that id.
                if not post(curl, base_url + "/v1/care-deltas", token, delta):
                    return
            state[key] = end
            directory = os.path.dirname(STATE) or "."
            fd, temp_path = tempfile.mkstemp(prefix=".codex-usage-", dir=directory)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as state_file:
                    json.dump(state, state_file)
                    state_file.flush()
                    os.fsync(state_file.fileno())
                os.replace(temp_path, STATE)
                os.chmod(STATE, 0o600)
            finally:
                if os.path.exists(temp_path):
                    os.unlink(temp_path)
        finally:
            if fcntl:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


def main() -> None:
    try:
        raw_payload = sys.stdin.buffer.read()
        payload = json.loads(raw_payload)
        session_id = payload.get("session_id") or payload.get("conversation_id")
        event_name = payload.get("hook_event_name")
        if not isinstance(session_id, str) or not session_id or not isinstance(event_name, str) or not event_name:
            return

        try:
            with open(CONFIG, encoding="utf-8") as file:
                config = json.load(file)
            base_url = str(config["url"]).rstrip("/")
            token = str(config["token"])
        except (OSError, ValueError, KeyError, TypeError):
            base_url, token = "", ""
        if base_url and not base_url.startswith("https://"):
            base_url = ""

        agent = "copilot" if "--agent" in sys.argv and sys.argv.index("--agent") + 1 < len(sys.argv) and sys.argv[sys.argv.index("--agent") + 1] == "copilot" else "codex"
        if agent == "codex" and event_name == "PermissionRequest":
            codex_permission_hook(payload, raw_payload, base_url, token)
            return
        if not base_url or not token:
            return

        model = payload.get("model")
        if isinstance(model, dict):
            model = model.get("display_name") or model.get("id")
        body = {
            "sessionId": session_id,
            "agentKind": agent,
            "eventName": event_name,
            "project": payload.get("cwd"),
            "message": payload.get("message") or payload.get("tool_name"),
            "model": model if isinstance(model, str) else None,
            "transcriptPath": payload.get("transcript_path"),
            "subagentId": payload.get("agent_id"),
            "timestamp": time.time(),
        }
        curl = shutil.which("curl")
        if not curl:
            return
        post(curl, base_url + "/v1/events", token, body)
        if agent == "codex":
            rollout = find_rollout(session_id, payload.get("transcript_path"))
            if rollout:
                new_usage_delta(rollout, session_id, curl, base_url, token, agent, payload.get("cwd"))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, subprocess.SubprocessError):
        pass


if __name__ == "__main__":
    main()
