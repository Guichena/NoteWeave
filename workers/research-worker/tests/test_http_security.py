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
