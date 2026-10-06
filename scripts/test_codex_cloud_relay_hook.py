"""Focused regression tests for authenticated Cloudflare approval requests."""

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


HOOK_PATH = Path(__file__).with_name("codex-cloud-relay-hook.py")
SPEC = importlib.util.spec_from_file_location("codex_cloud_relay_hook", HOOK_PATH)
hook = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(hook)

INSTALLER_PATH = Path(__file__).with_name("install-copilot-windows-hooks.py")
INSTALLER_SPEC = importlib.util.spec_from_file_location("install_copilot_windows_hooks", INSTALLER_PATH)
installer = importlib.util.module_from_spec(INSTALLER_SPEC)
assert INSTALLER_SPEC and INSTALLER_SPEC.loader
INSTALLER_SPEC.loader.exec_module(installer)


class RelayJsonTests(unittest.TestCase):
    @patch.object(hook.shutil, "which", return_value="/usr/bin/curl")
    @patch.object(hook.subprocess, "run")
    def test_posts_json_with_bearer_and_accepts_202(self, run, _which):
        run.return_value = subprocess.CompletedProcess(
            [], 0, b'{"ok":true,"requestId":"codex-test-12345678","state":"pending"}\nAGENTPET_HTTP_STATUS:202', b"")

        result = hook.relay_json("https://relay.example/v1/approvals", "secret-token", {
            "requestId": "codex-test-12345678", "sessionId": "test-session", "toolName": "Test"
        })

        self.assertEqual(result["state"], "pending")
        args, kwargs = run.call_args
        self.assertIn("Authorization: Bearer secret-token", args[0])
        self.assertIn("--data-binary", args[0])
        self.assertEqual(json.loads(kwargs["input"])["sessionId"], "test-session")

    @patch.object(hook.shutil, "which", return_value="/usr/bin/curl")
    @patch.object(hook.subprocess, "run")
    def test_rejects_http_error_even_when_error_body_is_json(self, run, _which):
        run.return_value = subprocess.CompletedProcess(
            [], 0, b'{"error":"agent token required"}\nAGENTPET_HTTP_STATUS:403', b"")

        self.assertIsNone(hook.relay_json("https://relay.example/v1/approvals", "token", {}))

    @patch.object(hook.shutil, "which", return_value="/usr/bin/curl")
    @patch.object(hook.subprocess, "run")
    def test_get_reads_the_decision_response(self, run, _which):
        run.return_value = subprocess.CompletedProcess(
            [], 0, b'{"requestId":"codex-test-12345678","state":"resolved","decision":"allow"}\nAGENTPET_HTTP_STATUS:200', b"")

        result = hook.relay_json("https://relay.example/v1/approvals/codex-test-12345678", "token")

        self.assertEqual(result["decision"], "allow")
        args, kwargs = run.call_args
        self.assertNotIn("--data-binary", args[0])
        self.assertIsNone(kwargs["input"])


class ApprovalDetailsTests(unittest.TestCase):
    def test_keeps_exact_command_separate_from_description(self):
        summary, execution = hook.approval_details({
            "description": "Run the test suite",
            "command": "npm test -- --runInBand",
            "cwd": "/workspace/project",
        })

        self.assertEqual(summary, "Run the test suite")
        self.assertIn("npm test -- --runInBand", execution)
        self.assertIn("Working directory: /workspace/project", execution)

    def test_exposes_command_when_no_description_is_supplied(self):
        summary, execution = hook.approval_details({"command": "rm generated.tmp"})

        self.assertIn("action details", summary)
        self.assertEqual(execution, "rm generated.tmp")

    def test_normalizes_wrapped_and_serialized_permission_inputs(self):
        for payload in (
            {"tool_input": {"command": "cargo test"}},
            {"input": {"command": "cargo test"}},
            {"tool_input": '{"command":"cargo test"}'},
            {"tool_input": "cargo test"},
            {"command": "cargo test", "hook_event_name": "PermissionRequest"},
        ):
            with self.subTest(payload=payload):
                details = hook.permission_tool_input(payload)
                _, execution = hook.approval_details(details)
                self.assertIn("cargo test", execution)

    def test_preserves_structured_non_shell_action_arguments(self):
        payload = {"tool_name": "apply_patch", "tool_input": {"patch": "*** Begin Patch\n..."}}

        self.assertEqual(hook.permission_tool_input(payload), payload["tool_input"])

    def test_normalizes_copilot_cli_permission_payload(self):
        payload = {"sessionId": "copilot-session", "toolName": "bash",
                   "toolArgs": {"command": "npm test", "cwd": "/workspace"}}
        self.assertEqual(hook.permission_tool_input(payload), payload["toolArgs"])
        summary, execution = hook.approval_details(hook.permission_tool_input(payload))
        self.assertTrue(summary)
        self.assertIn("npm test", execution)

    def test_returns_agent_native_permission_output(self):
        self.assertEqual(hook.permission_hook_output("copilot", "allow"), {"behavior": "allow"})
        self.assertEqual(hook.permission_hook_output("copilot", "deny"), {"behavior": "deny"})
        self.assertEqual(hook.permission_hook_output("codex", "allow"), {
            "hookSpecificOutput": {"hookEventName": "PermissionRequest",
                                   "decision": {"behavior": "allow"}}})

    def test_activity_mirror_keeps_copilot_prompt_and_tool_activity(self):
        self.assertEqual(hook.activity_message({"prompt": "Fix the relay"}), "Fix the relay")
        self.assertEqual(hook.activity_message({"toolName": "bash"}), "bash")
        self.assertIsNone(hook.activity_message({"sessionId": "session-only"}))


class WindowsCopilotInstallerTests(unittest.TestCase):
    def test_routes_native_agent_hooks_to_wsl_and_preserves_existing_hooks(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            home = root / "user"
            copilot_hooks = root / "copilot" / "hooks"
            copilot_hooks.mkdir(parents=True)
            copilot_path = copilot_hooks / "agentpet.json"
            copilot_original = {"version": 1, "hooks": {
                "PostToolUse": [{"type": "command", "command": "existing-hook"}]
            }}
            copilot_path.write_text(json.dumps(copilot_original), encoding="utf-8")
            codex_path = home / ".codex" / "hooks.json"
            codex_original = {"hooks": {"SessionStart": [{"hooks": [
                {"type": "command", "command": "existing-codex-hook"}
            ]}]}}
            codex_path.parent.mkdir(parents=True)
            codex_path.write_text(json.dumps(codex_original), encoding="utf-8")
            env = {"COPILOT_HOME": str(copilot_hooks.parent)}
            wsl_hook = "/home/example/.local/bin/agentpet-cloud-relay-hook"
            wsl_result = subprocess.CompletedProcess([], 0, wsl_hook + "\n", "")

            with patch.object(installer.Path, "home", return_value=home), \
                    patch.dict(os.environ, env, clear=False), \
                    patch.object(sys, "argv", [str(INSTALLER_PATH)]), \
                    patch.object(installer.subprocess, "run", return_value=wsl_result):
                installer.main()
                installer.main()

            copilot = json.loads(copilot_path.read_text(encoding="utf-8"))
            self.assertIn(copilot_original["hooks"]["PostToolUse"][0],
                          copilot["hooks"]["PostToolUse"])
            for event in (*installer.COPILOT_EVENTS, "PermissionRequest"):
                relay_entries = [entry for entry in copilot["hooks"][event]
                                 if isinstance(entry, dict) and "exec" in entry]
                self.assertEqual(len(relay_entries), 1, event)
                self.assertEqual(relay_entries[0]["args"][-2:], ["--agent", "copilot"])
                self.assertIn(wsl_hook, relay_entries[0]["args"])
                self.assertEqual(relay_entries[0]["timeoutSec"],
                                 200 if event == "PermissionRequest" else 15)
            codex = json.loads(codex_path.read_text(encoding="utf-8"))
            self.assertIn(codex_original["hooks"]["SessionStart"][0],
                          codex["hooks"]["SessionStart"])
            for event in (*installer.CODEX_EVENTS, "PermissionRequest"):
                groups = [group for group in codex["hooks"][event]
                          if isinstance(group, dict) and isinstance(group.get("hooks"), list)]
                relay_hooks = [item for group in groups for item in group["hooks"]
                               if isinstance(item, dict) and wsl_hook in item.get("command", "")]
                self.assertEqual(len(relay_hooks), 1, event)
                self.assertIn("--agent codex", relay_hooks[0]["command"])


if __name__ == "__main__":
    unittest.main()
