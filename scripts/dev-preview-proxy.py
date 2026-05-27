from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import select
import socket
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


BACKEND = "http://127.0.0.1:18082"
BACKEND_HOST = "127.0.0.1"
BACKEND_PORT = 18082
STATIC_ROOT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "static"
PROXY_PREFIXES = ("/api/", "/actuator", "/swagger-ui", "/v3/api-docs")
WEBSOCKET_PREFIXES = ("/ws/",)


class PreviewHandler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(STATIC_ROOT), **kwargs)

    def should_proxy(self):
        return urlsplit(self.path).path.startswith(PROXY_PREFIXES)

    def should_tunnel_websocket(self):
        path = urlsplit(self.path).path
        return (
            path.startswith(WEBSOCKET_PREFIXES)
            and self.headers.get("Upgrade", "").lower() == "websocket"
        )

    def tunnel_websocket(self):
        with socket.create_connection((BACKEND_HOST, BACKEND_PORT), timeout=10) as upstream:
            request = f"{self.command} {self.path} {self.request_version}\r\n"
            upstream.sendall(request.encode("iso-8859-1"))
            for key, value in self.headers.items():
                lower_key = key.lower()
                if lower_key == "host":
                    value = f"{BACKEND_HOST}:{BACKEND_PORT}"
                if lower_key in {"proxy-connection"}:
                    continue
                upstream.sendall(f"{key}: {value}\r\n".encode("iso-8859-1"))
            upstream.sendall(b"\r\n")

            self.connection.settimeout(None)
            upstream.settimeout(None)
            sockets = [self.connection, upstream]
            while True:
                readable, _, _ = select.select(sockets, [], [], 120)
                if not readable:
                    break
                for current in readable:
                    data = current.recv(65536)
                    if not data:
                        return
                    target = upstream if current is self.connection else self.connection
                    target.sendall(data)

    def proxy(self):
        target = BACKEND + self.path
        content_length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(content_length) if content_length else None
        headers = {
            key: value
            for key, value in self.headers.items()
            if key.lower() not in {"host", "connection", "content-length", "accept-encoding"}
        }
        request = Request(target, data=body, headers=headers, method=self.command)
        try:
            with urlopen(request, timeout=60) as response:
                self.write_proxy_response(response.status, response.headers.items(), response.read())
        except HTTPError as error:
            self.write_proxy_response(error.code, error.headers.items(), error.read())
        except URLError as error:
            data = f"Backend proxy failed: {error}".encode("utf-8")
            self.send_response(502)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(data)

    def write_proxy_response(self, status, headers, data):
        self.send_response(status)
        for key, value in headers:
            if key.lower() not in {"connection", "transfer-encoding", "content-encoding", "content-length"}:
                self.send_header(key, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def send_head(self):
        if self.should_tunnel_websocket():
            self.tunnel_websocket()
            return None
        if self.should_proxy():
            self.proxy()
            return None
        path = urlsplit(self.path).path
        target = (STATIC_ROOT / path.lstrip("/")).resolve()
        if path != "/" and (not target.exists() or not str(target).startswith(str(STATIC_ROOT.resolve()))):
            self.path = "/index.html"
        return super().send_head()

    def do_POST(self):
        self.proxy()

    def do_PUT(self):
        self.proxy()

    def do_PATCH(self):
        self.proxy()

    def do_DELETE(self):
        self.proxy()


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 5177), PreviewHandler)
    print(f"NoteWeave preview proxy: http://127.0.0.1:5177 -> {BACKEND}", flush=True)
    server.serve_forever()
