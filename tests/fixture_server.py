"""Serve synthetic media for device validation; no personal server is used."""
from pathlib import Path
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import threading
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

root = Path(__file__).resolve().parents[1] / '_testdata'
handler = partial(SimpleHTTPRequestHandler, directory=str(root))
http = ThreadingHTTPServer(('127.0.0.1', 8767), handler)
threading.Thread(target=http.serve_forever, daemon=True).start()
authorizer = DummyAuthorizer(); authorizer.add_user('test', 'test', str(root), perm='elr')
FTPHandler.authorizer = authorizer
FTPHandler.passive_ports = range(21211, 21221)
FTPServer(('127.0.0.1', 21210), FTPHandler).serve_forever()
