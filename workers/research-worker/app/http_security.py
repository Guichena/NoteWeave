from __future__ import annotations

import http.client
import io
import ipaddress
import socket
import ssl
import urllib.error
import urllib.parse
import urllib.request


class NoCredentialRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Fail closed instead of replaying provider credentials to a redirect."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        del req, fp, code, msg, headers, newurl
        return None


def credential_safe_urlopen(request: urllib.request.Request, *, timeout: float):
    parsed = urllib.parse.urlsplit(request.full_url)
    addresses = resolve_public_http_addresses(parsed)
    connect_ip = str(addresses[0])
    port = parsed.port or (443 if parsed.scheme.lower() == "https" else 80)
    if parsed.scheme.lower() == "https":
        connection: http.client.HTTPConnection = _PinnedHTTPSConnection(
            connect_ip, parsed.hostname or "", port, timeout
        )
    else:
        connection = http.client.HTTPConnection(connect_ip, port=port, timeout=timeout)
    headers = {key: value for key, value in request.header_items()}
    headers["Host"] = _host_header(parsed)
    headers["Connection"] = "close"
    path = urllib.parse.urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
    try:
        connection.request(request.get_method(), path, body=request.data, headers=headers)
        response = connection.getresponse()
        if response.status in {301, 302, 303, 307, 308}:
            body = response.read()
            raise urllib.error.HTTPError(
                request.full_url, response.status, "Provider redirects are forbidden",
                response.headers, io.BytesIO(body)
            )
        if response.status >= 400:
            body = response.read()
            raise urllib.error.HTTPError(
                request.full_url, response.status, response.reason,
                response.headers, io.BytesIO(body)
            )
        return _PinnedResponse(response, connection)
    except Exception:
        connection.close()
        raise


def validate_public_http_url(url: str) -> None:
    resolve_public_http_addresses(urllib.parse.urlsplit(str(url).strip()))


def resolve_public_http_addresses(
    parsed: urllib.parse.SplitResult,
) -> list[ipaddress.IPv4Address | ipaddress.IPv6Address]:
    if parsed.scheme.lower() not in {"http", "https"}:
        raise ValueError("URL_BLOCKED_SCHEME")
    if parsed.username or parsed.password:
        raise ValueError("URL_BLOCKED_CREDENTIALS")
    hostname = (parsed.hostname or "").strip().rstrip(".")
    if not hostname:
        raise ValueError("URL_BLOCKED_MISSING_HOST")
    if hostname.lower() in {"localhost", "localhost.localdomain", "metadata.google.internal"}:
        raise ValueError("URL_BLOCKED_LOCAL_HOST")
    try:
        direct_ip = ipaddress.ip_address(hostname)
    except ValueError:
        direct_ip = None
    addresses: list[ipaddress.IPv4Address | ipaddress.IPv6Address] = []
    if direct_ip is not None:
        addresses.append(direct_ip)
    else:
        try:
            for info in socket.getaddrinfo(
                hostname,
                parsed.port or (443 if parsed.scheme.lower() == "https" else 80),
                type=socket.SOCK_STREAM,
            ):
                address = ipaddress.ip_address(info[4][0])
                if address not in addresses:
                    addresses.append(address)
        except (socket.gaierror, ValueError) as exc:
            raise ValueError("URL_BLOCKED_DNS_RESOLUTION") from exc
    if not addresses or any(not address.is_global for address in addresses):
        raise ValueError("URL_BLOCKED_NON_PUBLIC_ADDRESS")
    return addresses


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, connect_ip: str, server_hostname: str, port: int, timeout: float) -> None:
        super().__init__(server_hostname, port=port, timeout=timeout, context=ssl.create_default_context())
        self._connect_ip = connect_ip

    def connect(self) -> None:
        raw_socket = socket.create_connection(
            (self._connect_ip, self.port), self.timeout, self.source_address
        )
        self.sock = self._context.wrap_socket(raw_socket, server_hostname=self.host)


class _PinnedResponse:
    def __init__(self, response: http.client.HTTPResponse, connection: http.client.HTTPConnection) -> None:
        self._response = response
        self._connection = connection

    def read(self, *args, **kwargs):  # noqa: ANN002, ANN003, ANN201
        return self._response.read(*args, **kwargs)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()
        return False

    def close(self) -> None:
        try:
            self._response.close()
        finally:
            self._connection.close()


def _host_header(parsed: urllib.parse.SplitResult) -> str:
    hostname = parsed.hostname or ""
    if ":" in hostname and not hostname.startswith("["):
        hostname = f"[{hostname}]"
    default_port = 443 if parsed.scheme.lower() == "https" else 80
    return f"{hostname}:{parsed.port}" if parsed.port and parsed.port != default_port else hostname
