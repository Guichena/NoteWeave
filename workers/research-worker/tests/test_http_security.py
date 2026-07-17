import urllib.request


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
