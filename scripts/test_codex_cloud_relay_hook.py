"""Focused regression tests for authenticated Cloudflare approval requests."""

import importlib.util
import json
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch


HOOK_PATH = Path(__file__).with_name("codex-cloud-relay-hook.py")
SPEC = importlib.util.spec_from_file_location("codex_cloud_relay_hook", HOOK_PATH)
hook = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(hook)


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


if __name__ == "__main__":
    unittest.main()
