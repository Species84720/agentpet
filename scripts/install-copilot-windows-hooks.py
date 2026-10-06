#!/usr/bin/env python3
"""Bridge native Windows Copilot/Codex permission hooks into AgentPet on WSL2.

Run with Windows Python. Hook payloads are piped through wsl.exe to the existing
AgentPet relay hook and desktop listener in the selected/default WSL distro.
No Windows AgentPet build or shell script is installed.
"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

COPILOT_EVENTS = ("SessionStart", "UserPromptSubmit", "PostToolUse", "Stop")
CODEX_EVENTS = ("SessionStart", "UserPromptSubmit", "PreToolUse", "Stop", "SubagentStop")


def wsl_hook_path(distro: str | None) -> str:
    command = ["wsl.exe"]
    if distro:
        command.extend(["--distribution", distro])
    command.extend([
        "--exec", "python3", "-c",
        "import os,pathlib,sys; p=pathlib.Path.home()/'.local/bin/agentpet-cloud-relay-hook'; "
        "print(p); sys.exit(0 if p.is_file() and os.access(p, os.X_OK) else 1)",
    ])
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=15, check=False)
    except (OSError, subprocess.SubprocessError) as error:
        raise SystemExit(f"Could not query the WSL2 AgentPet hook: {error}") from error
    hook_path = result.stdout.strip().splitlines()
    if result.returncode != 0 or not hook_path:
        detail = result.stderr.strip() or "the WSL hook was not found or is not executable"
        raise SystemExit(f"AgentPet's WSL2 relay hook is unavailable: {detail}")
    return hook_path[-1]


def read_config(path: Path, initial: dict) -> dict:
    try:
        config = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        config = initial
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"Cannot safely update {path}: {error}") from error
    if not isinstance(config, dict) or not isinstance(config.get("hooks", {}), dict):
        raise SystemExit(f"Unexpected hooks config structure in {path}")
    return config


def write_config(path: Path, config: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".agentpet-hooks-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(config, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distro", help="WSL distribution; defaults to your WSL default")
    args = parser.parse_args()

    home = Path.home()
    hook_path = wsl_hook_path(args.distro)
    wsl_args = []
    if args.distro:
        wsl_args.extend(["--distribution", args.distro])
    wsl_args.extend(["--exec", hook_path])

    # Native Copilot CLI uses direct executable + argument-array hook entries.
    copilot_home = Path(os.environ.get("COPILOT_HOME", home / ".copilot"))
    copilot_path = copilot_home / "hooks" / "agentpet.json"
    copilot = read_config(copilot_path, {"version": 1, "hooks": {}})
    copilot_events = copilot["hooks"]
    wsl_exe = Path(os.environ.get("WINDIR", r"C:\Windows")) / "System32" / "wsl.exe"
    copilot_exec = str(wsl_exe)
    for event in (*COPILOT_EVENTS, "PermissionRequest"):
        entries = copilot_events.setdefault(event, [])
        if not isinstance(entries, list):
            raise SystemExit(f"{event} must be an array in {copilot_path}")
        copilot_args = [*wsl_args, "--agent", "copilot"]
        hook = {"type": "command", "exec": copilot_exec, "args": copilot_args,
                "timeoutSec": 200 if event == "PermissionRequest" else 15}
        if not any(isinstance(entry, dict) and entry.get("exec") == copilot_exec
                   and entry.get("args") == copilot_args for entry in entries):
            entries.append(hook)
    write_config(copilot_path, copilot)

    # Codex's hook format is grouped. Add the WSL relay companion alongside the
    # existing local-pet hook on lifecycle events, plus the blocking approval
    # bridge. No PowerShell/Bash file is created or called from Windows.
    codex_path = home / ".codex" / "hooks.json"
    codex = read_config(codex_path, {"hooks": {}})
    codex_events = codex["hooks"]
    for event in (*CODEX_EVENTS, "PermissionRequest"):
        entries = codex_events.setdefault(event, [])
        if not isinstance(entries, list):
            raise SystemExit(f"{event} must be an array in {codex_path}")
        command_parts = [str(wsl_exe), *wsl_args, "--agent", "codex"]
        command = subprocess.list2cmdline(command_parts)
        installed = any(
            isinstance(group, dict) and isinstance(group.get("hooks"), list)
            and any(isinstance(item, dict) and item.get("command") == command
                    for item in group["hooks"])
            for group in entries
        )
        if not installed:
            entries.append({"hooks": [{"type": "command", "command": command}]})
    write_config(codex_path, codex)

    print(f"Copilot hook updated: {copilot_path}")
    print(f"Codex hook updated: {codex_path}")
    print(f"Lifecycle and permission hooks from both agents now forward to {hook_path} in WSL2.")
    print("Activity flows Windows agent → WSL2 relay hook → Cloudflare → Android pet.")
    print("Approval requests follow the same route; Android decisions return through Cloudflare to the waiting agent.")
    print("The relay URL/device token are read from ~/.agentpet/cloud-relay.json inside that WSL distro.")
    print("Android must be paired to the same Cloudflare relay account for requests and decisions to sync.")
    print("Restart native Windows Copilot CLI and Codex to load the hooks.")


if __name__ == "__main__":
    main()
