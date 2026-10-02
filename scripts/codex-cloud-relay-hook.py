#!/usr/bin/env python3
"""Best-effort Cloudflare mirror for Codex hooks.

This is deliberately a separate Codex hook command from AgentPet's local
AppImage hook. It reads the same stdin payload, posts a compatible AgentEvent
to the configured relay, and always exits 0: a phone or network outage must
never affect Codex or the local AgentPet integration.
"""

import json
import os
import shutil
import subprocess
import sys
import time


CONFIG = os.environ.get("AGENTPET_RELAY_CONFIG", os.path.expanduser("~/.agentpet/cloud-relay.json"))
TIMEOUT_SECONDS = 0.7


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
        data = json.dumps({key: value for key, value in body.items() if value is not None}).encode("utf-8")
        curl = shutil.which("curl")
        if not curl:
            return
        subprocess.run(
            [curl, "--fail", "--silent", "--show-error", "--max-time", str(TIMEOUT_SECONDS),
             "--request", "POST", base_url + "/v1/events",
             "--header", "Authorization: Bearer " + token,
             "--header", "Content-Type: application/json", "--data-binary", "@-"],
            input=data, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=TIMEOUT_SECONDS + 0.1, check=False,
        )
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, subprocess.SubprocessError):
        pass


if __name__ == "__main__":
    main()
