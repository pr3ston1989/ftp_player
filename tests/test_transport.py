"""Exercise real HTTP/FTP listings, byte seeking and protected account persistence."""
import http.server
import threading
import base64
import pytest
import requests

from ftp_player.core.models import Account, safe_path
from ftp_player.core.remote import RemoteClient
from ftp_player.core.accounts import AccountStore
from ftp_player.services.proxy import MediaProxy, byte_range


@pytest.fixture(params=['http', 'ftp'])
def remote(request, tmp_path):
    content = bytes(range(256)) * 1600
    (tmp_path/'film test.mkv').write_bytes(content)
    (tmp_path/'folder ze zdjęciami').mkdir()
    (tmp_path/'folder ze zdjęciami'/'obraz.jpg').write_bytes(b'image')
    if request.param == 'http':
        class Handler(http.server.SimpleHTTPRequestHandler):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, directory=str(tmp_path), **kwargs)

            def log_message(self, *args):
                pass

            def do_GET(self):
                if self.headers.get('Authorization') != 'Basic '+base64.b64encode(b'user:secret').decode():
                    self.send_error(401); return
                super().do_GET()

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        address = f'http://127.0.0.1:{server.server_port}/'
        cleanup = lambda: (server.shutdown(), server.server_close())
    else:
        from pyftpdlib.authorizers import DummyAuthorizer
        from pyftpdlib.handlers import FTPHandler
        from pyftpdlib.servers import FTPServer
        authorizer = DummyAuthorizer(); authorizer.add_user('user', 'secret', str(tmp_path), perm='elr')
        class Handler(FTPHandler):
            pass
        Handler.authorizer = authorizer
        server = FTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=lambda: server.serve_forever(timeout=.1, blocking=True, handle_exit=False), daemon=True)
        thread.start()
        address = f'ftp://127.0.0.1:{server.socket.getsockname()[1]}/'
        cleanup = server.close_all
    yield Account('test', 'Test', address, 'user', 'secret'), content
    cleanup(); thread.join(timeout=2)


def test_list_and_proxy_seek(remote):
    account, content = remote
    client = RemoteClient(account)
    entries = client.list('/')
    assert [e.name for e in entries] == ['folder ze zdjęciami', 'film test.mkv']
    metadata = client.stat(entries[1])
    assert metadata.size == len(content) and metadata.modified > 0 and metadata.metadata_known
    if account.scheme == 'http':
        metadata.size = 123
        metadata.size_exact = False
        assert client.size(metadata) == len(content)
    assert client.list(entries[0].path)[0].name == 'obraz.jpg'
    file = entries[1]
    proxy = MediaProxy()
    try:
        url = proxy.register(account, file)
        assert 'secret' not in url and 'user' not in url
        for header, expected, status in [(None, content, 200), ('bytes=100-199', content[100:200], 206),
                                          ('bytes=-150', content[-150:], 206), ('bytes=400000-', content[400000:], 206)]:
            response = requests.get(url, headers={'Range': header} if header else {}, timeout=5)
            assert response.status_code == status
            assert response.content == expected
            assert int(response.headers['Content-Length']) == len(expected)
        assert requests.head(url, timeout=5).headers['Content-Length'] == str(len(content))
        assert requests.get(url, headers={'Range':'bytes=999999999-'}, timeout=5).status_code == 416
        assert requests.get(url.replace(url.split('/')[3], 'invalid'), timeout=5).status_code == 404
    finally:
        proxy.close()


@pytest.mark.parametrize('header,expected', [('bytes=0-0',(0,0,True)),('bytes=8-',(8,9,True)),('bytes=-2',(8,9,True)),(None,(0,9,False))])
def test_range_boundaries(header, expected):
    assert byte_range(header, 10) == expected


@pytest.mark.parametrize('header', ['bytes=10-', 'bytes=-0', 'bytes=5-1', 'bytes=1-2,5-6', 'nonsense'])
def test_invalid_ranges(header):
    with pytest.raises(ValueError):
        byte_range(header, 10)


@pytest.mark.parametrize('path', ['../x','/../x','/safe/../../x','/bad\r\nRETR secret','/a\\b'])
def test_paths_cannot_escape_root_or_inject_commands(path):
    with pytest.raises(ValueError):
        safe_path(path)


def test_windows_accounts_are_encrypted_and_editable(tmp_path):
    import os
    if os.name != 'nt':
        pytest.skip('Windows DPAPI')
    store = AccountStore(tmp_path)
    accounts = [Account('one','Pierwszy','ftps://server/media/', 'user', 'very-private-password'),
                Account('two','Drugi','https://other/media/')]
    store.save(accounts)
    assert 'very-private-password' not in store.path.read_text()
    assert store.load() == accounts
    accounts[0].password = 'changed'
    store.save(accounts)
    assert store.load()[0].password == 'changed'
    assert 'changed' not in repr(accounts[0])


def test_autoindex_scope_and_encoded_names():
    from ftp_player.core.remote import IndexParser
    parser = IndexParser(); parser.feed('<a href="x%20y.mkv">x</a><a href="../">up</a>')
    assert parser.links == ['x%20y.mkv','../']
    client = RemoteClient(Account('id','Test','https://host/base/'))
    assert client.url('/folder with space/') == 'https://host/base/folder%20with%20space/'
    assert client.remote_path('/video.mkv') == '/base/video.mkv'
