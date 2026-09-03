#!/usr/bin/env python3
"""API 反向代理 - 转发请求到远端 API"""

import http.server
import json
import urllib.request
import ssl
import threading
import sys

UPSTREAM = "https://mimo.ezlook.top/v1"
API_KEY = "sk-o81YYYSV72e7ZSei1DvEBypVZPLw6ad1X6aa-UMB2g8"
PORT = 8765

class ProxyHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        # 读取请求体
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length)

        # 构造上游请求
        url = UPSTREAM + self.path
        req = urllib.request.Request(url, data=body, method='POST')
        req.add_header('Content-Type', self.headers.get('Content-Type', 'application/json'))
        req.add_header('Authorization', f'Bearer {API_KEY}')

        try:
            ctx = ssl.create_default_context()
            with urllib.request.urlopen(req, context=ctx, timeout=120) as resp:
                resp_body = resp.read()
                self.send_response(resp.status)
                for key, val in resp.getheaders():
                    if key.lower() not in ('transfer-encoding', 'connection'):
                        self.send_header(key, val)
                self.end_headers()
                self.wfile.write(resp_body)
        except Exception as e:
            error_msg = json.dumps({"error": str(e)}).encode()
            self.send_response(502)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(error_msg)

    def do_GET(self):
        # /v1/models 透传获取模型列表
        url = UPSTREAM + self.path
        req = urllib.request.Request(url, method='GET')
        req.add_header('Authorization', f'Bearer {API_KEY}')

        try:
            ctx = ssl.create_default_context()
            with urllib.request.urlopen(req, context=ctx, timeout=30) as resp:
                resp_body = resp.read()
                self.send_response(resp.status)
                for key, val in resp.getheaders():
                    if key.lower() not in ('transfer-encoding', 'connection'):
                        self.send_header(key, val)
                self.end_headers()
                self.wfile.write(resp_body)
        except Exception as e:
            error_msg = json.dumps({"error": str(e)}).encode()
            self.send_response(502)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(error_msg)

    def log_message(self, fmt, *args):
        print(f"[{self.log_date_time_string()}] {fmt % args}")

def main():
    server = http.server.HTTPServer(('0.0.0.0', PORT), ProxyHandler)
    print(f"代理服务已启动: http://localhost:{PORT}")
    print(f"转发目标: {UPSTREAM}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
        server.server_close()

if __name__ == '__main__':
    main()
