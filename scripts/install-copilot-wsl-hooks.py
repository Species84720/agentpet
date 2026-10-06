#!/usr/bin/env python3
"""Add AgentPet's permission bridge to the current user's Copilot CLI hooks."""

import json
import os
from pathlib import Path
import tempfile


def main() -> None:
    home = Path.home()
    hooks_dir = Path(os.environ.get("COPILOT_HOME", home / ".copilot")) / "hooks"
    config_path = hooks_dir / "agentpet.json"
    hook_path = Path(os.environ.get(
        "AGENTPET_HOOK_PATH", home / ".local/bin/agentpet-cloud-relay-hook"
    )).expanduser()
    if not hook_path.is_file():
        raise SystemExit(f"AgentPet relay hook not found: {hook_path}")

    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        config = {"version": 1, "hooks": {}}
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"Cannot safely update {config_path}: {error}") from error

    if not isinstance(config, dict) or not isinstance(config.get("hooks", {}), dict):
        raise SystemExit(f"Unexpected Copilot hooks config structure in {config_path}")
    hooks = config.setdefault("hooks", {})
    entries = hooks.setdefault("PermissionRequest", [])
    if not isinstance(entries, list):
        raise SystemExit(f"PermissionRequest must be an array in {config_path}")

    target = str(hook_path)
    already_installed = any(
        isinstance(entry, dict) and entry.get("exec") == target
        and entry.get("args") == ["--agent", "copilot"]
        for entry in entries
    )
    if already_installed:
        print(f"AgentPet Copilot permission hook already configured in {config_path}")
        return

    entries.append({
        "type": "command",
        "exec": target,
        "args": ["--agent", "copilot"],
        "timeoutSec": 200,
    })
    hooks_dir.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".agentpet-copilot-", dir=hooks_dir)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(config, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, config_path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print(f"Installed AgentPet Copilot permission hook in {config_path}")


if __name__ == "__main__":
    main()
