"""Persistent per-server navigation, bookmarks and stable browser sorting."""
from dataclasses import asdict
from hashlib import sha256
from pathlib import Path
import json
import os
import re
from .accounts import data_dir
from .models import MediaFile, safe_path


def server_key(account):
    return sha256((account.id + ':' + account.address + ':' + account.username).encode()).hexdigest()


def sort_entries(entries, key='name', descending=False):
    """Keep folders first and unknown metadata last in both directions."""
    def natural(value):
        return [int(p) if p.isdigit() else p for p in re.split(r'(\d+)', value.casefold())]
    def value(entry):
        return {'name': natural(entry.name), 'size': entry.size, 'modified': entry.modified,
                'ext': entry.ext}.get(key, natural(entry.name))
    folders = sorted((e for e in entries if e.directory), key=lambda e: natural(e.name))
    files = sorted((e for e in entries if not e.directory), key=lambda e: natural(e.name))
    known, missing = [], []
    for entry in files:
        unknown = (key == 'size' and not entry.metadata_known and not entry.size) or (key == 'modified' and not entry.modified)
        (missing if unknown else known).append(entry)
    return folders + sorted(known, key=value, reverse=descending) + missing


class Library:
    """Persist only paths and media metadata; passwords stay in the account store."""
    def __init__(self, directory=None):
        self.path = Path(directory or data_dir()) / 'library.json'
        self.data = {}
        if self.path.exists():
            try:
                loaded = json.loads(self.path.read_text(encoding='utf-8'))
                self.data = loaded if isinstance(loaded, dict) else {}
            except (OSError, ValueError):
                pass

    def state(self, account):
        return self.data.setdefault(server_key(account), {'last': '/', 'start': '', 'folders': [], 'files': []})

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding='utf-8')
        os.replace(temporary, self.path)

    def startup(self, account):
        state = self.state(account)
        try:
            return safe_path(state.get('start') or state.get('last') or '/')
        except (ValueError, TypeError):
            return '/'

    def visited(self, account, path):
        self.state(account)['last'] = safe_path(path)
        self.save()

    def set_start(self, account, path=''):
        self.state(account)['start'] = safe_path(path) if path else ''
        self.save()

    def toggle_folder(self, account, path):
        folders = self.state(account)['folders']
        path = safe_path(path)
        if path in folders:
            folders.remove(path)
        else:
            folders.append(path)
        self.save()

    def toggle_file(self, account, entry):
        files = self.state(account)['files']
        existing = next((row for row in files if row['path'] == entry.path), None)
        if existing:
            files.remove(existing)
        else:
            row = asdict(entry); row.pop('url', None)
            files.append(row)
        self.save()

    def favorites(self, account):
        return [MediaFile(**row) for row in self.state(account)['files']]
