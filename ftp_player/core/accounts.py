"""Store profile metadata and protect passwords with Windows DPAPI."""
import base64
import ctypes as ct
import json
import os
from pathlib import Path
from dataclasses import asdict
from .models import Account


def data_dir():
    return Path(os.environ.get('APPDATA', Path.home() / '.config')) / 'FTPPlayer'


class _Blob(ct.Structure):
    _fields_ = [('size', ct.c_uint32), ('data', ct.POINTER(ct.c_ubyte))]


def _protect(value: bytes, decrypt=False) -> bytes:
    """Use per-user Windows encryption; never fall back to plaintext."""
    if os.name != 'nt':
        raise RuntimeError('Trwały zapis haseł tej wersji wymaga Windows.')
    buffer = ct.create_string_buffer(value)
    source = _Blob(len(value), ct.cast(buffer, ct.POINTER(ct.c_ubyte)))
    target = _Blob()
    api = ct.WinDLL('crypt32', use_last_error=True)
    name = 'CryptUnprotectData' if decrypt else 'CryptProtectData'
    function = getattr(api, name)
    function.argtypes = [ct.POINTER(_Blob), ct.c_void_p, ct.c_void_p, ct.c_void_p,
                         ct.c_void_p, ct.c_uint32, ct.POINTER(_Blob)]
    function.restype = ct.c_int
    if not function(ct.byref(source), None, None, None, None, 1, ct.byref(target)):
        raise ct.WinError(ct.get_last_error())
    try:
        return ct.string_at(target.data, target.size)
    finally:
        kernel = ct.WinDLL('kernel32')
        kernel.LocalFree.argtypes = [ct.c_void_p]
        kernel.LocalFree(target.data)


class AccountStore:
    def __init__(self, directory=None):
        self.directory = Path(directory) if directory else data_dir()
        self.path = self.directory / 'accounts.json'

    def load(self):
        if not self.path.exists():
            return []
        result = []
        for row in json.loads(self.path.read_text(encoding='utf-8')):
            secret = row.pop('secret', '')
            row['password'] = _protect(base64.b64decode(secret), True).decode() if secret else ''
            result.append(Account(**row))
        return result

    def save(self, accounts):
        rows = []
        for account in accounts:
            row = asdict(account)
            password = row.pop('password')
            row['secret'] = base64.b64encode(_protect(password.encode())).decode() if password else ''
            rows.append(row)
        self.directory.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
        os.replace(temporary, self.path)
