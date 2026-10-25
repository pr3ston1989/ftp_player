"""Small cross-platform filename utilities."""
import re


def sanitize_filename(name):
    return re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', name).strip(' .') or 'media'
