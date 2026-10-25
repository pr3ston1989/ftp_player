"""Ikony UI z czcionki systemowej Windows (Segoe Fluent Icons / MDL2 Assets).

Makeover "Zinc & Amber": zastępujemy emoji w toolbarze prawdziwymi glifami
ikonowymi. Zero zależności — Windows 10/11 mają font ikon wbudowany; gdy go
brak (np. Linux), toolbar wraca do przycisków tekstowych (fallback w App).

Kodepointy zgodne z Segoe MDL2 Assets (Win10) i Segoe Fluent Icons (Win11;
nadzbiór MDL2) — użycie wyłącznie szeroko udokumentowanych glifów.
"""

from __future__ import annotations

import tkinter.font as tkfont
from typing import Optional

# Nazwa -> kodepoint (Segoe MDL2 / Fluent Icons)
_GLYPHS = {
    "refresh": "\uE72C",       # Refresh
    "sync": "\uE895",          # Sync (pełny skan)
    "add": "\uE710",           # Add
    "rename": "\uE8AC",        # Rename
    "delete": "\uE74D",        # Delete
    "download": "\uE896",      # Download
    "upload": "\uE898",        # Upload
    "search": "\uE721",        # Search
    "folder": "\uE8B7",        # Folder
    "settings": "\uE713",      # Settings
    "star": "\uE734",          # FavoriteStar
    "play": "\uE768",          # Play
    "close": "\uE711",         # Cancel
}

_CANDIDATE_FAMILIES = ("Segoe Fluent Icons", "Segoe MDL2 Assets")


def icon_font_family(root) -> Optional[str]:
    """Zwraca nazwę dostępnej czcionki ikon (Fluent→MDL2) albo None."""
    try:
        families = set(tkfont.families(root))
    except Exception:  # noqa: BLE001 — brak displaya w testach headless
        return None
    for family in _CANDIDATE_FAMILIES:
        if family in families:
            return family
    return None


def icon_font(root, size: int = 13) -> Optional[tkfont.Font]:
    """Zwraca gotowy obiekt czcionki ikon albo None (brak fontu w systemie).

    WAŻNE: zwracamy OBIEKT Font, nie string „rodzina rozmiar". Nazwy rodzin
    ikonowych są wielowyrazowe ("Segoe MDL2 Assets"), a Tk parsuje taki string
    jako listę {rodzina rozmiar ...} → „expected integer but got MDL2".
    ttk.Style zjada ten błąd po cichu i rysuje przycisk domyślnym fontem, w
    którym glify z Private Use Area nie istnieją — przycisk wychodził pusty
    (zero szerokości tekstu), czyli praktycznie niewidoczny na toolbarze.

    Wywołujący MUSI trzymać referencję do zwróconego obiektu — po odśmieceniu
    Font usuwa nazwany font z Tcl i styl znów traci czcionkę.
    """
    family = icon_font_family(root)
    if family is None:
        return None
    try:
        return tkfont.Font(root=root, family=family, size=size)
    except Exception:  # noqa: BLE001 — brak displaya / egzotyczny backend Tk
        return None


def glyph(name: str) -> str:
    """Zwraca znak glifu (lub pusty string dla nieznanej nazwy)."""
    return _GLYPHS.get(name, "")


__all__ = ["icon_font_family", "icon_font", "glyph"]
