"""Local-only command-backed fault controller for Docker distributed replay.

The harness executes only argv arrays from an explicit JSON spec.  It never
uses a shell, and it acknowledges a scenario only after the command exits 0.
Scenario commands may block until their target Run reaches the required state.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


SUPPORTED_SCENARIOS = {
    "worker_crash",
    "lease_expiry",
    "duplicate_delivery",
    "completion_response_loss",
    "quorum_disagreement",
    "audit_blocker",
    "hydrated_resume",
}


class FaultController:
    def __init__(self, commands: dict[str, list[str]], *, timeout_seconds: float) -> None:
        self.commands = commands
        self.timeout_seconds = timeout_seconds

    def apply(self, scenario: str, run_id: str) -> dict[str, object]:
        normalized = scenario.strip().lower()
        if normalized not in SUPPORTED_SCENARIOS:
            raise ValueError("unsupported fault scenario")
        command = self.commands.get(normalized)
        if not command or any(not isinstance(item, str) or not item for item in command):
            raise ValueError("fault scenario has no configured argv command")
        argv = [item.replace("{run_id}", run_id) for item in command]
        completed = subprocess.run(
            argv,
            check=False,
            capture_output=True,
            text=True,
            timeout=self.timeout_seconds,
            shell=False,
        )
        if completed.returncode != 0:
            raise RuntimeError(f"fault command failed with exit code {completed.returncode}")
        evidence = completed.stdout.strip().splitlines()[-1] if completed.stdout.strip() else "exit:0"
        return {"scenario": normalized.upper(), "applied": True, "evidence": evidence[:1000]}


def _handler(controller: FaultController):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802 - stdlib callback name
            prefix = "/scenarios/"
            if not self.path.startswith(prefix):
                self._write(404, {"success": False, "code": "NOT_FOUND"})
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if size < 2 or size > 16_384:
                    raise ValueError("invalid request size")
                payload = json.loads(self.rfile.read(size).decode("utf-8"))
                run_id = str(payload.get("research_run_id") or "").strip()
                scenario = self.path[len(prefix):].strip("/")
                if not run_id or str(payload.get("scenario") or "").lower() != scenario:
                    raise ValueError("fault request identity mismatch")
                result = controller.apply(scenario, run_id)
                self._write(200, {"success": True, "data": result})
            except (ValueError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError) as exc:
                self._write(409, {"success": False, "code": "FAULT_NOT_APPLIED", "message": str(exc)})

        def log_message(self, _format, *_args):
            return

        def _write(self, status: int, payload: dict[str, object]) -> None:
            encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

    return Handler


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the local Docker distributed replay fault controller")
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18093)
    parser.add_argument("--timeout-seconds", type=float, default=180)
    args = parser.parse_args(argv)
    payload = json.loads(args.spec.read_text(encoding="utf-8"))
    commands = payload.get("commands") if isinstance(payload, dict) else None
    if not isinstance(commands, dict):
        raise ValueError("fault harness spec requires a commands object")
    controller = FaultController(commands, timeout_seconds=args.timeout_seconds)
    server = ThreadingHTTPServer((args.host, args.port), _handler(controller))
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
