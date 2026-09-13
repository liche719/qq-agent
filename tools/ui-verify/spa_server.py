"""本地验证用：托管 Vue 构建产物，把 /api/admin/* 代理到远程网关（注入真实口令），
并模拟后端新增的 /api/admin/session 登录校验接口（真实实现部署到服务器后再验证）。"""
import base64
import http.server
import json
import os
import ssl
import urllib.request

ROOT = r"C:\Users\33721\Desktop\wechat-agent\wechat-agent-java\src\main\resources\static"
UPSTREAM = "https://120.25.170.92:8443"
KEY = os.environ["WG_PW"]
USERNAME = os.environ.get("ADMIN_USERNAME", "rootlcw")
AUTH = base64.b64encode(("admin:" + os.environ["WG_PW"]).encode()).decode()
CTX = ssl._create_unverified_context()
PORT = 8899


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def _send(self, status, payload, content_type="application/json"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _session(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode() or "{}")
        except Exception:  # noqa: BLE001
            body = {}
        username = str(body.get("username", "")).strip()
        provided = self.headers.get("X-Agent-Admin-Key") or ""
        if username.lower() == USERNAME and provided == KEY:
            self._send(200, {"ok": True, "username": USERNAME})
        else:
            self._send(401, {"message": "账号或口令不正确"})

    def do_POST(self):
        if self.path == "/api/admin/session":
            self._session()
            return
        self.do_proxy("POST")

    def do_GET(self):
        if self.path.startswith("/api/admin"):
            self.do_proxy("GET")
            return
        super().do_GET()

    def do_proxy(self, method):
        length = int(self.headers.get("Content-Length") or 0)
        payload = self.rfile.read(length) if length else None
        request = urllib.request.Request(UPSTREAM + self.path, data=payload, method=method)
        request.add_header("Authorization", "Basic " + AUTH)
        request.add_header("X-Agent-Admin-Key", KEY)
        if payload:
            request.add_header("Content-Type", self.headers.get("Content-Type", "application/json"))
        try:
            with urllib.request.urlopen(request, context=CTX, timeout=25) as response:
                body = response.read()
                self.send_response(response.status)
                self.send_header("Content-Type", response.headers.get("Content-Type", "application/json"))
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        except urllib.error.HTTPError as error:
            self.send_response(error.code)
            self.send_header("Content-Length", "0")
            self.end_headers()
        except Exception as error:  # noqa: BLE001
            self.send_response(502)
            self.send_header("Content-Length", "0")
            self.send_header("X-Proxy-Error", str(error)[:120])
            self.end_headers()

    def log_message(self, *args):
        pass


with http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler) as httpd:
    print("serving on http://127.0.0.1:%d/" % PORT, flush=True)
    httpd.serve_forever()
