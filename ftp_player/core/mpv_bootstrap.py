"""Locate bundled libmpv without downloading executables at runtime."""
from pathlib import Path
import os
import sys

_handles = []
_paths = set()


def ensure_libmpv_in_path():
    if os.name != 'nt':
        return
    candidates = [Path(sys.executable).parent, Path(__file__).resolve().parents[2] / 'native']
    if getattr(sys, '_MEIPASS', None):
        candidates.insert(0, Path(sys._MEIPASS))
    for path in candidates:
        path = path.resolve()
        if path.is_dir() and path not in _paths:
            os.environ['PATH'] = str(path) + os.pathsep + os.environ.get('PATH', '')
            _handles.append(os.add_dll_directory(str(path)))
            _paths.add(path)
