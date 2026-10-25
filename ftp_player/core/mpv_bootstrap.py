"""Locate bundled libmpv without downloading executables at runtime."""
from pathlib import Path
import os
import sys

_handles = []


def ensure_libmpv_in_path():
    if os.name != 'nt':
        return
    for path in (Path(getattr(sys, '_MEIPASS', '')), Path(sys.executable).parent,
                 Path(__file__).resolve().parents[2] / 'native'):
        if path.is_dir():
            os.environ['PATH'] = str(path) + os.pathsep + os.environ.get('PATH', '')
            _handles.append(os.add_dll_directory(str(path)))
