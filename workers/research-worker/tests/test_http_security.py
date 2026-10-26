import urllib.parse
import urllib.request

import pytest


def test_credentialed_provider_requests_should_refuse_redirects() -> None:
    from app.http_security import NoCredentialRedirectHandler

    handler = NoCredentialRedirectHandler()
    original = urllib.request.Request(
        "https://provider.example/v1/search",
        headers={"Authorization": "Bearer secret", "X-API-Key": "secret"},
    )

    redirected = handler.redirect_request(
        original, None, 302, "Found", {}, "https://attacker.example/collect"
    )

    assert redirected is None


@pytest.mark.parametrize(
    "url",
    [
        "http://127.0.0.1/private",
        "http://169.254.169.254/latest/meta-data",
        "http://[::1]/private",
        "http://2130706433/private",
        "http://0177.0.0.1/private",
    ],
)
def test_credentialed_provider_requests_should_reject_non_public_targets(url: str) -> None:
    from app.http_security import credential_safe_urlopen

    with pytest.raises(ValueError, match="URL_BLOCKED"):
        credential_safe_urlopen(urllib.request.Request(url), timeout=1)


def test_credentialed_provider_request_should_connect_to_the_validated_ip(monkeypatch) -> None:
    from app.http_security import credential_safe_urlopen

    connections: list[tuple[str, int]] = []
    requests: list[tuple[str, str, dict[str, str]]] = []

    monkeypatch.setattr(
        "app.http_security.socket.getaddrinfo",
        lambda *_args, **_kwargs: [(2, 1, 6, "", ("93.184.216.34", 80))],
    )

    class Response:
        status = 200
        reason = "OK"
        headers = {}

        def read(self, *_args, **_kwargs) -> bytes:
            return b"{}"

        def close(self) -> None:
            return None

    class Connection:
        def __init__(self, host: str, port: int, timeout: float) -> None:
            del timeout
            connections.append((host, port))

        def request(self, method: str, path: str, body, headers: dict[str, str]) -> None:
            del body
            requests.append((method, path, headers))

        def getresponse(self) -> Response:
            return Response()

        def close(self) -> None:
            return None

    monkeypatch.setattr("app.http_security.http.client.HTTPConnection", Connection)

    with credential_safe_urlopen(
        urllib.request.Request("http://provider.example/v1/search", method="POST"), timeout=1
    ) as response:
        assert response.read() == b"{}"

    assert connections == [("93.184.216.34", 80)]
    assert requests[0][0:2] == ("POST", "/v1/search")
    assert requests[0][2]["Host"] == "provider.example"


def test_docker_synthetic_dns_requires_explicit_opt_in(monkeypatch) -> None:
    from app.http_security import resolve_public_http_addresses

    monkeypatch.setattr(
        "app.http_security.socket.getaddrinfo",
        lambda *_args, **_kwargs: [(2, 1, 6, "", ("198.18.0.29", 443))],
    )

    with pytest.raises(ValueError, match="URL_BLOCKED_NON_PUBLIC_ADDRESS"):
        resolve_public_http_addresses(urllib.parse.urlsplit("https://provider.example/search"))

    monkeypatch.setenv("NOTEWEAVE_ALLOW_DOCKER_SYNTHETIC_DNS", "true")
    assert [str(item) for item in resolve_public_http_addresses(
        urllib.parse.urlsplit("https://provider.example/search")
    )] == ["198.18.0.29"]


def test_docker_synthetic_literal_ip_remains_blocked(monkeypatch) -> None:
    from app.http_security import resolve_public_http_addresses

    monkeypatch.setenv("NOTEWEAVE_ALLOW_DOCKER_SYNTHETIC_DNS", "true")
    with pytest.raises(ValueError, match="URL_BLOCKED_NON_PUBLIC_ADDRESS"):
        resolve_public_http_addresses(urllib.parse.urlsplit("https://198.18.0.29/search"))


class _KeepAliveDrip:
    """响应头之后每 0.2 秒发一个空格，模拟服务端处理时持续发送保活数据、迟迟不给正文。"""

    def __enter__(self):
        import threading
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        stop = threading.Event()

        class Handler(BaseHTTPRequestHandler):
            def _drip(self) -> None:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", "100000")
                self.end_headers()
                try:
                    while not stop.wait(0.2):
                        self.wfile.write(b" ")
                        self.wfile.flush()
                except OSError:
                    return

            do_GET = do_POST = _drip

            def log_message(self, *_args) -> None:
                return None

        self.stop = stop
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://provider.example:{self.server.server_address[1]}/v1/tools/fetch"
        return self

    def __exit__(self, *_args):
        self.stop.set()
        self.server.shutdown()
        self.server.server_close()
        return False


def test_provider_request_that_keeps_sending_keepalive_bytes_stops_at_the_total_deadline(monkeypatch) -> None:
    import ipaddress
    import time

    from app.http_security import ResponseDeadlineExceeded, credential_safe_urlopen

    monkeypatch.setattr("app.http_security.resolve_public_http_addresses",
                        lambda _parsed: [ipaddress.ip_address("127.0.0.1")])
    with _KeepAliveDrip() as server:
        started = time.monotonic()
        # 每次读取都在单次超时内收到数据，只有总时限能让请求结束
        request = urllib.request.Request(server.url, data=b"{}", method="POST")
        with (pytest.raises(ResponseDeadlineExceeded),
              credential_safe_urlopen(request, timeout=1, total_timeout=1.5) as response):
            response.read()
        assert time.monotonic() - started < 5


def test_webpage_that_drips_bytes_stops_at_the_total_deadline_and_is_not_retried(monkeypatch) -> None:
    import ipaddress
    import time

    from app.fetch_adapters import (
        _is_retryable_fetch_error,
        _non_retryable_fetch_failure_code,
        _pinned_http_get,
    )
    from app.http_security import ResponseDeadlineExceeded

    monkeypatch.setattr("app.fetch_adapters.validate_public_http_url", lambda _url: None)
    monkeypatch.setattr("app.fetch_adapters._resolve_public_addresses",
                        lambda _parsed: [ipaddress.ip_address("127.0.0.1")])
    monkeypatch.setattr("app.fetch_adapters.TOTAL_TIMEOUT_FACTOR", 1.5)
    with _KeepAliveDrip() as server:
        started = time.monotonic()
        with pytest.raises(ResponseDeadlineExceeded) as raised:
            _pinned_http_get(server.url, headers={}, timeout_seconds=1, token_budget=4000)
        assert time.monotonic() - started < 5
    assert not _is_retryable_fetch_error(raised.value)
    assert _non_retryable_fetch_failure_code(raised.value) == "FETCH_DEADLINE_EXCEEDED"
