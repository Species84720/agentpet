#!/usr/bin/env python3
"""Best-effort Cloudflare mirror for Codex hooks.

This is deliberately a separate Codex hook command from AgentPet's local
AppImage hook. It reads the same stdin payload, posts a compatible AgentEvent
to the configured relay, and always exits 0: a phone or network outage must
never affect Codex or the local AgentPet integration.
"""

import json
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import time

try:
    import fcntl
except ImportError:  # pragma: no cover - Windows fallback
    fcntl = None


CONFIG = os.environ.get("AGENTPET_RELAY_CONFIG", os.path.expanduser("~/.agentpet/cloud-relay.json"))
TIMEOUT_SECONDS = 0.7
STATE = os.environ.get("AGENTPET_RELAY_USAGE_STATE", os.path.expanduser("~/.agentpet/cloud-relay-codex-usage.json"))
LOCK = STATE + ".lock"


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
        with open(CONFIG, encoding="utf-8") as file:
            config = json.load(file)
        base_url = str(config["url"]).rstrip("/")
        token = str(config["token"])
        if not base_url.startswith("https://") or not token:
            return

        payload = json.load(sys.stdin)
        session_id = payload.get("session_id") or payload.get("conversation_id")
        event_name = payload.get("hook_event_name")
        if not isinstance(session_id, str) or not session_id or not isinstance(event_name, str) or not event_name:
            return

        model = payload.get("model")
        if isinstance(model, dict):
            model = model.get("display_name") or model.get("id")
        agent = "copilot" if "--agent" in sys.argv and sys.argv.index("--agent") + 1 < len(sys.argv) and sys.argv[sys.argv.index("--agent") + 1] == "copilot" else "codex"
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
