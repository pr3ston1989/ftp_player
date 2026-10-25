"""Launch the Windows application."""
from .core.mpv_bootstrap import ensure_libmpv_in_path


def main():
    ensure_libmpv_in_path()
    try:
        from pillow_heif import register_heif_opener
        register_heif_opener()
    except ImportError:
        pass
    from .gui.app import App
    App().mainloop()


if __name__ == '__main__':
    main()
