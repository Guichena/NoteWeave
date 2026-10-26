from __future__ import annotations

import http.client
import io
import ipaddress
import os
import socket
import ssl
import threading
import urllib.error
import urllib.parse
import urllib.request


class NoCredentialRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Fail closed instead of replaying provider credentials to a redirect."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        del req, fp, code, msg, headers, newurl
        return None


# timeout 只限制相邻两次收到数据的间隔。服务端处理时持续发送保活空行或慢速返回时，单个请求可以挂住
# 几个小时（2026-10-26 两个取证任务的网页读取请求各挂了五个多小时），所以整个请求另设总时限
TOTAL_TIMEOUT_FACTOR = 4


class ResponseDeadlineExceeded(TimeoutError):
    """整个请求超过总时限，连接已被关闭。"""


class ResponseDeadline:
    """请求总时限：到时关闭底层套接字，阻塞中的读取随即返回，之后的读取一律报超时。"""

    def __init__(self, seconds: float) -> None:
        self._done = threading.Event()
        self._lock = threading.Lock()
        self._sock: socket.socket | None = None
        self.expired = False
        threading.Thread(target=self._watch, args=(max(0.1, float(seconds)),),
                         name="http-response-deadline", daemon=True).start()

    def attach(self, sock: socket.socket | None) -> None:
        """请求发出后记下套接字。带 Connection: close 时，拿到响应后连接对象会把 sock 置空，套接字只留在响应里。"""
        with self._lock:
            self._sock = sock
            expired = self.expired
        if expired:
            _abort_socket(sock)

    def _watch(self, seconds: float) -> None:
        if self._done.wait(seconds):
            return
        with self._lock:
            # 请求恰好在到时那一刻结束的，不再算超时
            if self._done.is_set():
                return
            self.expired = True
            _abort_socket(self._sock)

    def cancel(self) -> None:
        with self._lock:
            self._done.set()

    def check(self, cause: BaseException | None = None) -> None:
        if self.expired:
            raise ResponseDeadlineExceeded("RESPONSE_DEADLINE_EXCEEDED") from cause


def _abort_socket(sock: socket.socket | None) -> None:
    if sock is None:
        return
    try:
        # 直接关闭文件描述符上的读写；SSLSocket.shutdown 会先丢弃 TLS 状态，不能在别的线程读取时调用
        socket.socket.shutdown(sock, socket.SHUT_RDWR)
    except OSError:
        pass


def credential_safe_urlopen(request: urllib.request.Request, *, timeout: float, total_timeout: float | None = None):
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
    deadline = ResponseDeadline(total_timeout or timeout * TOTAL_TIMEOUT_FACTOR)
    try:
        connection.request(request.get_method(), path, body=request.data, headers=headers)
        deadline.attach(getattr(connection, "sock", None))
        response = connection.getresponse()
        deadline.check()
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
        return _PinnedResponse(response, connection, deadline)
    except Exception as exc:
        deadline.cancel()
        connection.close()
        deadline.check(exc)
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
    allow_synthetic_dns = (
        direct_ip is None
        and os.getenv("NOTEWEAVE_ALLOW_DOCKER_SYNTHETIC_DNS", "").strip().lower()
        in {"1", "true", "yes", "on"}
    )
    docker_synthetic_network = ipaddress.ip_network("198.18.0.0/15")
    if not addresses or any(
        not address.is_global
        and not (allow_synthetic_dns and address in docker_synthetic_network)
        for address in addresses
    ):
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
    def __init__(self, response: http.client.HTTPResponse, connection: http.client.HTTPConnection,
                 deadline: ResponseDeadline) -> None:
        self._response = response
        self._connection = connection
        self._deadline = deadline

    def read(self, *args, **kwargs):  # noqa: ANN002, ANN003, ANN201
        # 到时关闭连接后，读取可能报错，也可能把截断的正文当成完整内容返回，两种情况都按超时处理
        try:
            body = self._response.read(*args, **kwargs)
        except Exception as exc:
            self._deadline.check(exc)
            raise
        self._deadline.check()
        return body

    @property
    def headers(self):  # noqa: ANN201
        return self._response.headers

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()
        return False

    def close(self) -> None:
        self._deadline.cancel()
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
