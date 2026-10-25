"""Standalone Windows launcher with an offline distribution verification mode."""
from pathlib import Path
import os
import sys

if getattr(sys, 'frozen', False):
    os.environ['PATH'] = sys._MEIPASS + os.pathsep + os.environ.get('PATH', '')
    if sys.stdout is None:
        sys.stdout = open(os.devnull, 'w')
    if sys.stderr is None:
        sys.stderr = open(os.devnull, 'w')

if len(sys.argv) == 3 and sys.argv[1] == '--self-test':
    import json
    import tkinter as tk
    from PIL import Image
    from ftp_player.core.mpv_bootstrap import ensure_libmpv_in_path
    ensure_libmpv_in_path()
    from ftp_player.gui.player import mpv_available
    from ftp_player.gui.app import App
    root = tk.Tk(); root.withdraw()
    image = Image.open(Path(sys._MEIPASS) / 'assets/icon.png')
    result = {'tk': bool(root.tk.call('info', 'patchlevel')), 'image': image.width == 512,
              'mpv': mpv_available(), 'app': App is not None}
    root.destroy()
    Path(sys.argv[2]).write_text(json.dumps(result), encoding='utf-8')
    raise SystemExit(0 if all(result.values()) else 1)

from ftp_player.__main__ import main
main()
