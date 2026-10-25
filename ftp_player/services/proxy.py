"""Tokenized loopback media bridge with correct HTTP byte ranges for FTP and HTTPS."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import mimetypes
import secrets
import threading
import re
from ..core.remote import RemoteClient
from ..core.network_traffic import APP_TRAFFIC


def byte_range(header, size):
    """Parse one byte range, including suffix requests, or reject it."""
    if not header:
        return 0, max(0, size - 1), False
    match = re.fullmatch(r'bytes=(\d*)-(\d*)', header)
    if not match or not any(match.groups()) or size <= 0:
        raise ValueError('Invalid range')
    start = int(match[1]) if match[1] else max(0, size - int(match[2]))
    end = min(size - 1, int(match[2])) if match[1] and match[2] else size - 1
    if start >= size or end < start or (not match[1] and int(match[2]) == 0):
        raise ValueError('Unsatisfiable range')
    return start, end, True


class MediaProxy:
    def __init__(self):
        owner = self
        self._entries = {}
        self._lock = threading.Lock()

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, *args):
                pass

            def do_HEAD(self):
                self.serve(False)

            def do_GET(self):
                self.serve(True)

            def serve(self, body):
                self.connection.settimeout(45)
                token = urlsplit(self.path).path.split('/')[1:2]
                with owner._lock:
                    item = owner._entries.get(token[0] if token else '')
                if item is None:
                    self.send_error(404)
                    return
                client, entry = item
                headers_sent = False
                try:
                    size = client.size(entry)
                    if size:
                        entry.size = size
                    try:
                        start, end, partial = byte_range(self.headers.get('Range'), size)
                    except ValueError:
                        self.send_response(416)
                        self.send_header('Content-Range', f'bytes */{size}')
                        self.send_header('Content-Length', '0')
                        self.end_headers()
                        return

                    def headers():
                        self.close_connection = True
                        self.send_response(206 if partial else 200)
                        self.send_header('Content-Type', mimetypes.guess_type(entry.name)[0] or 'application/octet-stream')
                        self.send_header('Accept-Ranges', 'bytes')
                        self.send_header('Content-Length', str(end - start + 1 if size else 0))
                        self.send_header('Cache-Control', 'no-store')
                        self.send_header('Connection', 'close')
                        if partial:
                            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
                        self.end_headers()

                    if not body or not size:
                        headers()
                        return
                    with client.read(entry, start) as stream:
                        headers()
                        headers_sent = True
                        remaining = end - start + 1
                        while remaining:
                            data = stream.read(min(256 * 1024, remaining))
                            if not data:
                                break
                            if client.account.scheme in ('ftp', 'ftps'):
                                APP_TRAFFIC.add(received=len(data))
                            self.wfile.write(data)
                            remaining -= len(data)
                        if remaining:
                            self.close_connection = True
                except (BrokenPipeError, ConnectionResetError):
                    self.close_connection = True
                except Exception:
                    # Dane uwierzytelniające i pełne URL nigdy nie trafiają do błędu HTTP.
                    self.close_connection = True
                    if not headers_sent:
                        self.send_error(502, 'Nie udalo sie odczytac pliku z serwera')

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def register(self, account, entry):
        token = secrets.token_urlsafe(24)
        with self._lock:
            self._entries[token] = RemoteClient(account), entry
        address = f'http://127.0.0.1:{self.server.server_port}/{token}/{entry.name.rsplit(".", 1)[-1]}'
        entry.url = address
        return address

    def close(self):
        self.server.shutdown()
        self.server.server_close()
