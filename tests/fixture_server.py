"""Serve synthetic media for device validation; no personal server is used."""
from pathlib import Path
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import threading
import os
import re
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

root = Path(__file__).resolve().parents[1] / '_testdata'
class RangeHandler(SimpleHTTPRequestHandler):
    def send_head(self):
        if self.path.startswith('/no-range/'):
            self.path = self.path[len('/no-range'):]
            if 'Range' in self.headers: del self.headers['Range']
        path = self.translate_path(self.path)
        if os.path.isfile(path) and self.headers.get('Range'):
            match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers['Range'])
            if match:
                file = open(path, 'rb'); size = os.fstat(file.fileno()).st_size
                start = int(match[1]); end = min(size-1, int(match[2])) if match[2] else size-1
                if start >= size or end < start:
                    file.close(); self.send_error(416); return None
                print('RANGE', os.path.basename(path), start, end, flush=True)
                self.send_response(206); self.send_header('Content-Type', self.guess_type(path))
                self.send_header('Content-Length', str(end-start+1)); self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
                self.send_header('Accept-Ranges', 'bytes'); self.end_headers()
                file.seek(start); self.remaining = end-start+1; return file
        self.remaining = None
        return super().send_head()

    def copyfile(self, source, outputfile):
        remaining = self.remaining
        try:
            while remaining is None or remaining > 0:
                data = source.read(65536 if remaining is None else min(65536, remaining))
                if not data: break
                outputfile.write(data)
                if remaining is not None: remaining -= len(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

handler = partial(RangeHandler, directory=str(root))
http = ThreadingHTTPServer(('127.0.0.1', 8767), handler)
threading.Thread(target=http.serve_forever, daemon=True).start()
authorizer = DummyAuthorizer(); authorizer.add_user('test', 'test', str(root), perm='elr')
FTPHandler.authorizer = authorizer
FTPHandler.passive_ports = range(21211, 21221)
FTPServer(('127.0.0.1', 21210), FTPHandler).serve_forever()
