"""Internal G3 proxy: observe commit, drop response, gate exact replay."""

from __future__ import annotations

import hashlib
import http.client
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


LISTEN_HOST = os.environ.get("MA4G_PROXY_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("MA4G_PROXY_LISTEN_PORT", "8089"))
UPSTREAM_HOST = os.environ.get("MA4G_PROXY_UPSTREAM_HOST", "backend")
UPSTREAM_PORT = int(os.environ.get("MA4G_PROXY_UPSTREAM_PORT", "8081"))
CONTROL = Path(os.environ.get("MA4G_PROXY_CONTROL_DIR", "/control"))
WAIT_SECONDS = int(os.environ.get("MA4G_PROXY_REPLAY_WAIT_SECONDS", "25"))
HOP_HEADERS = {"connection", "proxy-connection", "keep-alive", "transfer-encoding", "upgrade"}


class ReplayState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.first_body: bytes | None = None
        self.first_idempotency: str | None = None
        self.first_response: dict[str, Any] | None = None
        self.first_status: int | None = None
        self.replay_seen = False


STATE = ReplayState()


def atomic_json(name: str, value: object) -> None:
    path = CONTROL / name
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    os.replace(temporary, path)


def parsed_json(raw: bytes) -> dict[str, Any]:
    try:
        value = json.loads(raw.decode("utf-8"))
    except Exception as exc:
        raise RuntimeError("G3 upstream returned invalid UTF-8 JSON") from exc
    if not isinstance(value, dict):
        raise RuntimeError("G3 upstream response is not an object")
    return value


def receipt_summary(wrapper: dict[str, Any]) -> dict[str, Any]:
    data = wrapper.get("data")
    if not isinstance(data, dict):
        raise RuntimeError("G3 upstream response has no receipt data")
    keys = (
        "schema_version", "completion_id", "execution_id", "task_id", "completion_digest",
        "receipt_digest", "idempotent_replay", "evidence_appended", "candidate_count",
    )
    return {key: data.get(key) for key in keys}


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: object) -> None:
        print("MA4G_G3_PROXY " + (fmt % args), flush=True)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/healthz":
            self._reply(200, b"ok", {"Content-Type": "text/plain"})
            return
        self._ordinary_proxy()

    def do_POST(self) -> None:  # noqa: N802
        if self.path.startswith("/internal/research-agent-tasks/") and self.path.endswith("/complete"):
            self._completion_proxy()
            return
        self._ordinary_proxy()

    def _read_body(self) -> bytes:
        raw_length = self.headers.get("Content-Length", "0")
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise RuntimeError("invalid client content length") from exc
        if length < 0 or length > 256 * 1024:
            raise RuntimeError("client body length is outside the MA4G bound")
        return self.rfile.read(length) if length else b""

    def _upstream(self, body: bytes) -> tuple[int, dict[str, str], bytes]:
        headers = {
            key: value for key, value in self.headers.items()
            if key.lower() not in HOP_HEADERS | {"host", "content-length"}
        }
        if body:
            headers["Content-Length"] = str(len(body))
        connection = http.client.HTTPConnection(UPSTREAM_HOST, UPSTREAM_PORT, timeout=35)
        try:
            connection.request(self.command, self.path, body=body or None, headers=headers)
            response = connection.getresponse()
            response_body = response.read()
            response_headers = {
                key: value for key, value in response.getheaders()
                if key.lower() not in HOP_HEADERS | {"content-length"}
            }
            return response.status, response_headers, response_body
        finally:
            connection.close()

    def _ordinary_proxy(self) -> None:
        try:
            body = self._read_body()
            status, headers, response = self._upstream(body)
            self._reply(status, response, headers)
        except Exception as exc:
            print(f"MA4G_G3_PROXY_ERROR type={type(exc).__name__}", flush=True)
            self._reply(502, b"upstream proxy failure", {"Content-Type": "text/plain"})

    def _completion_proxy(self) -> None:
        try:
            body = self._read_body()
            digest = hashlib.sha256(body).hexdigest()
            idempotency = self.headers.get("X-NoteWeave-Idempotency-Key", "")
            with STATE.lock:
                is_first = STATE.first_body is None
                if is_first:
                    STATE.first_body = body
                    STATE.first_idempotency = idempotency
                else:
                    if STATE.replay_seen:
                        raise RuntimeError("G3 observed more than one replay request")
                    STATE.replay_seen = True
                    if body != STATE.first_body or idempotency != STATE.first_idempotency:
                        raise RuntimeError("G3 replay bytes or idempotency header changed")

            if is_first:
                status, headers, response = self._upstream(body)
                wrapper = parsed_json(response)
                receipt = receipt_summary(wrapper)
                if status != 200 or receipt.get("idempotent_replay") is not False:
                    self._reply(status, response, headers)
                    raise RuntimeError("G3 first completion was not a fresh successful commit")
                with STATE.lock:
                    STATE.first_status = status
                    STATE.first_response = receipt
                atomic_json("g3-first-commit.json", {
                    "request_sha256": digest,
                    "request_size_bytes": len(body),
                    "idempotency_key": idempotency,
                    "response_status": status,
                    "receipt": receipt,
                })
                print(f"MA4G_G3_FIRST_COMMIT_RESPONSE_DROPPED sha256={digest}", flush=True)
                self.close_connection = True
                return

            atomic_json("g3-replay-waiting.json", {
                "request_sha256": digest,
                "request_size_bytes": len(body),
                "idempotency_key": idempotency,
            })
            print(f"MA4G_G3_IDENTICAL_REPLAY_WAITING sha256={digest}", flush=True)
            deadline = time.monotonic() + WAIT_SECONDS
            while not (CONTROL / "g3-release-replay").exists():
                if time.monotonic() >= deadline:
                    raise RuntimeError("G3 timed out waiting for the replay release barrier")
                time.sleep(0.1)

            status, headers, response = self._upstream(body)
            wrapper = parsed_json(response)
            receipt = receipt_summary(wrapper)
            with STATE.lock:
                first_status = STATE.first_status
                first_receipt = STATE.first_response
                first_idempotency = STATE.first_idempotency
                first_body = STATE.first_body
            atomic_json("g3-proof.json", {
                "request_sha256": digest,
                "request_size_bytes": len(body),
                "first_request_sha256": hashlib.sha256(first_body or b"").hexdigest(),
                "first_request_size_bytes": len(first_body or b""),
                "idempotency_key": idempotency,
                "first_idempotency_key": first_idempotency,
                "first_response_status": first_status,
                "second_response_status": status,
                "first_receipt": first_receipt,
                "second_receipt": receipt,
            })
            print(f"MA4G_G3_IDENTICAL_REPLAY_FORWARDED sha256={digest}", flush=True)
            self._reply(status, response, headers)
        except Exception as exc:
            atomic_json("g3-proxy-error.json", {"error_type": type(exc).__name__})
            print(f"MA4G_G3_PROXY_FATAL type={type(exc).__name__}", flush=True)
            if not self.wfile.closed:
                try:
                    self._reply(502, b"G3 proxy invariant failed", {"Content-Type": "text/plain"})
                except OSError:
                    pass

    def _reply(self, status: int, body: bytes, headers: dict[str, str]) -> None:
        self.send_response(status)
        for key, value in headers.items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        if body:
            self.wfile.write(body)
        self.close_connection = True


def main() -> None:
    CONTROL.mkdir(parents=True, exist_ok=True)
    for name in (
        "g3-first-commit.json", "g3-replay-waiting.json", "g3-release-replay",
        "g3-proof.json", "g3-proxy-error.json",
    ):
        try:
            (CONTROL / name).unlink()
        except FileNotFoundError:
            pass
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), ProxyHandler)
    print(f"MA4G_G3_PROXY_READY port={LISTEN_PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

