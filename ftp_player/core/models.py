"""Server profiles and immutable remote entries."""
from dataclasses import dataclass, field
from hashlib import sha256
from urllib.parse import urlsplit, unquote
import posixpath

VIDEO = set('mp4 mkv avi mov wmv mpg mpeg m1v m2v rm rmvb flv webm m4v ts mts m2ts vob ogv 3gp hevc xvid divx asf'.split())
AUDIO = set('mp3 flac wav aac m4a ogg opus wma aiff ape alac ac3 dts mka amr mid'.split())
IMAGES = set('jpg jpeg png webp gif bmp tif tiff ico avif heic heif'.split())


@dataclass
class Account:
    id: str
    name: str
    address: str
    username: str = ''
    password: str = field(default='', repr=False)

    def __post_init__(self):
        parsed = urlsplit(self.address.strip())
        if parsed.scheme not in ('https', 'http', 'ftp', 'ftps') or not parsed.hostname:
            raise ValueError('Podaj pełny adres: https://serwer/katalog/ lub ftps://serwer/')
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError('Login i hasło wpisz w osobnych polach. Adres nie może zawierać parametrów.')
        if any(c in self.address + self.username + self.password for c in '\r\n\x00'):
            raise ValueError('Niedozwolony znak w danych serwera.')
        _ = parsed.port
        self.address = self.address.strip().rstrip('/') + '/'

    @property
    def scheme(self):
        return urlsplit(self.address).scheme

    @property
    def root(self):
        return posixpath.normpath(unquote(urlsplit(self.address).path) or '/')


@dataclass
class MediaFile:
    name: str
    path: str
    size: int = 0
    directory: bool = False
    account_id: str = ''
    url: str | None = None

    @property
    def id(self):
        return sha256((self.account_id + ':' + self.path).encode()).hexdigest()[:24]

    @property
    def ext(self):
        return self.name.rsplit('.', 1)[-1].lower() if '.' in self.name else ''

    @property
    def kind(self):
        return 'folder' if self.directory else 'video' if self.ext in VIDEO else 'audio' if self.ext in AUDIO else 'image' if self.ext in IMAGES else 'file'


def safe_path(path: str) -> str:
    """Normalize an account-relative path without accepting traversal or commands."""
    if any(c in path for c in ('\r', '\n', '\x00', '\\')) or '..' in path.split('/'):
        raise ValueError('Nieprawidłowa ścieżka.')
    return '/' + posixpath.normpath('/' + path.lstrip('/')).lstrip('/')
