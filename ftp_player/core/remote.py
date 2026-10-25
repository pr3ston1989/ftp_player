"""Browse HTTPS autoindexes and FTP/FTPS servers with isolated connections."""
from contextlib import contextmanager
from html.parser import HTMLParser
from urllib.parse import urlsplit, urljoin, quote, unquote
import ftplib
import posixpath
import ssl
import re
from .models import MediaFile, safe_path
from .network_traffic import measured_session


class IndexParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links = []

    def handle_starttag(self, tag, attrs):
        if tag.lower() == 'a':
            href = dict(attrs).get('href')
            if href:
                self.links.append(href)


class RemoteClient:
    def __init__(self, account):
        self.account = account

    def remote_path(self, path):
        return self.account.root.rstrip('/') + safe_path(path)

    def url(self, path):
        clean = safe_path(path)
        if path.endswith('/') and not clean.endswith('/'):
            clean += '/'
        return self.account.address.rstrip('/') + quote(clean, safe='/')

    @contextmanager
    def ftp(self):
        parsed = urlsplit(self.account.address)
        client = ftplib.FTP_TLS(context=ssl.create_default_context()) if parsed.scheme == 'ftps' else ftplib.FTP()
        client.encoding = 'utf-8'
        try:
            client.connect(parsed.hostname, parsed.port or 21, timeout=20)
            client.login(self.account.username or 'anonymous', self.account.password or '')
            if isinstance(client, ftplib.FTP_TLS):
                client.prot_p()
            client.set_pasv(True)
            yield client
        finally:
            client.close()

    @contextmanager
    def http(self, method, path, **kwargs):
        with measured_session() as session:
            response = session.request(method, self.url(path),
                auth=(self.account.username, self.account.password) if self.account.username else None,
                timeout=(15, 40), allow_redirects=False, **kwargs)
            try:
                if 300 <= response.status_code < 400:
                    raise OSError('Serwer przekierowuje adres. Wpisz docelowy adres katalogu w ustawieniach konta.')
                response.raise_for_status()
                yield response
            finally:
                response.close()

    def list(self, path='/'):
        path = safe_path(path)
        rows = []
        if self.account.scheme in ('ftp', 'ftps'):
            with self.ftp() as ftp:
                remote = self.remote_path(path)
                try:
                    entries = [(name, facts.get('type') == 'dir', int(facts.get('size', 0)))
                               for name, facts in ftp.mlsd(remote)
                               if facts.get('type') not in ('cdir', 'pdir')]
                except ftplib.error_perm:
                    ftp.cwd(remote)
                    lines = []
                    ftp.retrlines('LIST', lines.append)
                    entries = []
                    for line in lines:
                        match = re.match(r'^([d\-l])\S*\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+\S+\s+(.+)$', line)
                        if match and match[1] != 'l':
                            entries.append((match[3], match[1] == 'd', int(match[2])))
                        else:
                            dos = re.match(r'^\S+\s+\S+\s+(<DIR>|\d+)\s+(.+)$', line)
                            if dos:
                                entries.append((dos[2], dos[1] == '<DIR>', 0 if dos[1] == '<DIR>' else int(dos[1])))
                for name, directory, size in entries:
                    if name not in ('.', '..') and '/' not in name and '\\' not in name:
                        rows.append(MediaFile(name, safe_path(posixpath.join(path, name)), size, directory, self.account.id))
        else:
            directory_path = path.rstrip('/') + '/'
            with self.http('GET', directory_path, stream=True) as response:
                data = bytearray()
                for chunk in response.iter_content(64 * 1024):
                    data.extend(chunk)
                    if len(data) > 4 * 1024 * 1024:
                        raise OSError('Lista katalogu przekracza 4 MB.')
                parser = IndexParser()
                parser.feed(data.decode(response.encoding if response.encoding and response.encoding.lower() != 'iso-8859-1' else 'utf-8', errors='replace'))
                base = self.url(directory_path)
                base_parts = urlsplit(base)
                seen = set()
                for link in parser.links:
                    address = urlsplit(urljoin(base, link))
                    if (address.scheme, address.netloc) != (base_parts.scheme, base_parts.netloc) or address.query or address.fragment:
                        continue
                    decoded, prefix = unquote(address.path), unquote(base_parts.path)
                    if not decoded.startswith(prefix):
                        continue
                    name = decoded[len(prefix):].rstrip('/')
                    if not name or '/' in name or name in ('.', '..') or name in seen:
                        continue
                    seen.add(name)
                    rows.append(MediaFile(name, safe_path(posixpath.join(path, name)), 0,
                                          address.path.endswith('/'), self.account.id))
        return sorted(rows, key=lambda row: (not row.directory, row.name.casefold()))

    def size(self, entry):
        if entry.size:
            return entry.size
        if self.account.scheme in ('ftp', 'ftps'):
            with self.ftp() as ftp:
                ftp.voidcmd('TYPE I')
                return int(ftp.size(self.remote_path(entry.path)) or 0)
        with self.http('HEAD', entry.path) as response:
            return int(response.headers.get('Content-Length', 0))

    @contextmanager
    def read(self, entry, start=0):
        if self.account.scheme in ('ftp', 'ftps'):
            with self.ftp() as ftp:
                ftp.voidcmd('TYPE I')
                connection = ftp.transfercmd('RETR ' + self.remote_path(entry.path), rest=start or None)
                connection.settimeout(40)
                try:
                    with connection.makefile('rb', buffering=64 * 1024) as stream:
                        yield stream
                finally:
                    connection.close()
        else:
            headers = {'Range': f'bytes={start}-', 'Accept-Encoding': 'identity'} if start else {'Accept-Encoding': 'identity'}
            with self.http('GET', entry.path, headers=headers, stream=True) as response:
                if start and response.status_code == 206:
                    if not response.headers.get('Content-Range', '').startswith(f'bytes {start}-'):
                        raise OSError('Serwer zwrócił nieprawidłowy zakres pliku.')
                elif start:
                    # Serwery bez Range: pomijamy początek, nigdy nie podszywamy
                    # danych od zera pod odpowiedź zaczynającą się w innym miejscu.
                    remaining = start
                    while remaining:
                        data = response.raw.read(min(64 * 1024, remaining))
                        if not data:
                            raise OSError('Plik jest krótszy niż żądana pozycja.')
                        remaining -= len(data)
                yield response.raw
