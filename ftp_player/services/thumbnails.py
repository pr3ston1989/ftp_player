"""Bounded, persistent three-frame video previews using the bundled FFmpeg."""
from pathlib import Path
from hashlib import sha256
import json
import os
import shutil
import subprocess
import time
import threading
from PIL import Image
from ..core.accounts import data_dir
from ..core.library import server_key


class ThumbnailCache:
    def __init__(self, directory=None, limit=256 * 1024 * 1024):
        self.directory = Path(directory or data_dir() / 'thumbnails')
        self.directory.mkdir(parents=True, exist_ok=True)
        self.limit = limit
        self._lock = threading.Lock()
        self._failed = {}

    def key(self, account, entry):
        stamp = f'{server_key(account)}:{entry.path}:{entry.size}:{entry.modified}:v2'
        return sha256(stamp.encode()).hexdigest()

    def cached(self, account, entry):
        key = self.key(account, entry)
        paths = [self.directory / f'{key}-{index}.jpg' for index in range(3)]
        # Serwery bez mtime nie dają pewnej sygnatury zmiany: ograniczony TTL.
        ttl = 30 * 86400 if entry.modified else 7 * 86400
        if all(p.is_file() and time.time() - p.stat().st_mtime < ttl for p in paths):
            return paths
        return []

    def create(self, account, entry, url, cancelled=lambda: False):
        key = self.key(account, entry)
        if self._failed.get(key, 0) > time.monotonic():
            return []
        with self._lock:
            cached = self.cached(account, entry)
            if cached or cancelled():
                return cached
            ffmpeg, ffprobe = shutil.which('ffmpeg'), shutil.which('ffprobe')
            if not ffmpeg or not ffprobe:
                return []
            flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
            try:
                probe = subprocess.Popen([ffprobe, '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', url],
                                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, creationflags=flags)
                deadline = time.monotonic() + 25
                while probe.poll() is None:
                    if cancelled() or time.monotonic() > deadline:
                        probe.kill(); probe.wait(); raise TimeoutError()
                    time.sleep(.08)
                output, _ = probe.communicate()
                if probe.returncode:
                    raise OSError('Media probe failed')
                duration = float(json.loads(output)['format']['duration'])
                if duration <= 0:
                    return []
                jitter = int(key[:4], 16) / 65535 * .08 - .04
                paths = []
                for index, fraction in enumerate((.32 + jitter, .5 + jitter, .7 + jitter)):
                    if cancelled():
                        return []
                    target = self.directory / f'{key}-{index}.jpg'
                    temporary = target.with_suffix('.tmp.jpg')
                    try:
                        command = [ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
                                   '-ss', str(max(0, duration * fraction)), '-i', url, '-frames:v', '1', '-an', '-sn',
                                   '-vf', 'scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2',
                                   '-q:v', '4', str(temporary)]
                        process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=flags)
                        deadline = time.monotonic() + 25
                        while process.poll() is None:
                            if cancelled() or time.monotonic() > deadline:
                                process.kill(); process.wait(); raise TimeoutError()
                            time.sleep(.08)
                        if process.returncode:
                            raise OSError('Thumbnail decode failed')
                        with Image.open(temporary) as image:
                            image.verify()
                        os.replace(temporary, target)
                        paths.append(target)
                    finally:
                        temporary.unlink(missing_ok=True)
                self.trim()
                return paths
            except (OSError, ValueError, KeyError, subprocess.SubprocessError, TimeoutError):
                self._failed[key] = time.monotonic() + 300
                return []

    def trim(self):
        files = sorted(self.directory.glob('*.jpg'), key=lambda p: p.stat().st_mtime)
        total = sum(p.stat().st_size for p in files)
        for path in files:
            if total <= self.limit:
                break
            total -= path.stat().st_size
            path.unlink(missing_ok=True)
