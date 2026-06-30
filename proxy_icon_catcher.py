"""
代理服务器：自动抓取一木记账彩色分类图标

原理：
  - 请求一律放行，不做任何处理
  - 响应边转发边复制，发现 colorIcon 图片就保存一份，继续放行
"""

import os
import socket
import threading
import select

HOST = "0.0.0.0"
PORT = 8900
TARGET_DOMAIN = "yimu-category.oss-cn-beijing.aliyuncs.com"
TARGET_PATH = "/colorIcon/"
SAVE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "colorIcon")

os.makedirs(SAVE_DIR, exist_ok=True)


def parse_host_port(host_header, default_port=80):
    if ":" in host_header:
        parts = host_header.rsplit(":", 1)
        return parts[0], int(parts[1])
    return host_header, default_port


def forward(client_sock, remote_sock, capture_file=None):
    """
    双向转发。
    capture_file 不为 None 时，把 remote→client 的数据同时写入该文件。
    """
    sockets = [client_sock, remote_sock]
    captured = bytearray()
    try:
        while True:
            readable, _, _ = select.select(sockets, [], [], 30)
            if not readable:
                break
            for sock in readable:
                data = sock.recv(65536)
                if not data:
                    # 连接结束，保存捕获的数据
                    if capture_file and captured:
                        with open(capture_file, "wb") as f:
                            f.write(bytes(captured))
                        print(f"[Icon] 保存: {os.path.basename(capture_file)} ({len(captured)} bytes)")
                    return
                if sock is client_sock:
                    remote_sock.sendall(data)
                else:
                    client_sock.sendall(data)
                    if capture_file:
                        captured.extend(data)
    except (OSError, ConnectionError):
        pass
    finally:
        if capture_file and captured:
            try:
                with open(capture_file, "wb") as f:
                    f.write(bytes(captured))
                print(f"[Icon] 保存: {os.path.basename(capture_file)} ({len(captured)} bytes)")
            except:
                pass


def handle_client(client_sock, addr):
    try:
        # 读取完整请求头
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = client_sock.recv(4096)
            if not chunk:
                client_sock.close()
                return
            data += chunk

        header_end = data.index(b"\r\n\r\n")
        headers_raw = data[:header_end].decode(errors="replace")
        first_line = headers_raw.split("\r\n")[0]
        parts = first_line.split(" ")
        if len(parts) < 3:
            client_sock.close()
            return

        method, target, version = parts

        # CONNECT 隧道（HTTPS）
        if method.upper() == "CONNECT":
            host, port = parse_host_port(target, 443)
            try:
                remote_sock = socket.create_connection((host, port), timeout=10)
                client_sock.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                forward(client_sock, remote_sock)
                remote_sock.close()
            except:
                try:
                    client_sock.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
                except:
                    pass
            client_sock.close()
            return

        # HTTP 代理请求
        if target.startswith("http://"):
            url_no_scheme = target[7:]
            path_start = url_no_scheme.find("/")
            if path_start == -1:
                host_port = url_no_scheme
                path = "/"
            else:
                host_port = url_no_scheme[:path_start]
                path = url_no_scheme[path_start:]
            host, port = parse_host_port(host_port)
        else:
            client_sock.close()
            return

        # 判断是否需要捕获响应
        capture_file = None
        if host == TARGET_DOMAIN and TARGET_PATH in path:
            filename = path.split("/")[-1]
            if filename and filename.endswith(".png"):
                filepath = os.path.join(SAVE_DIR, filename)
                if not os.path.exists(filepath):
                    capture_file = filepath
                    print(f"[Icon] 捕获: {filename}")
                else:
                    print(f"[Icon] 已存在: {filename}")

        # 转发请求（去掉 Proxy-Connection 头）
        try:
            remote_sock = socket.create_connection((host, port), timeout=10)
            lines = headers_raw.split("\r\n")
            new_lines = [first_line]
            for line in lines[1:]:
                if line.lower().startswith("proxy-connection:"):
                    continue
                new_lines.append(line)
            remote_sock.sendall(("\r\n".join(new_lines) + "\r\n\r\n").encode())

            # 双向转发，同时捕获响应
            forward(client_sock, remote_sock, capture_file)
            remote_sock.close()
        except:
            try:
                client_sock.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
            except:
                pass

        client_sock.close()

    except Exception as e:
        print(f"[Proxy] 异常: {e}")
        try:
            client_sock.close()
        except:
            pass


def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(100)
    print(f"[Proxy] 启动: 0.0.0.0:{PORT}")
    print(f"[Proxy] 目标: {TARGET_DOMAIN}{TARGET_PATH}")
    print(f"[Proxy] 保存: {SAVE_DIR}")

    while True:
        try:
            client_sock, addr = server.accept()
            threading.Thread(target=handle_client, args=(client_sock, addr), daemon=True).start()
        except KeyboardInterrupt:
            break
        except:
            pass

    server.close()


if __name__ == "__main__":
    main()
