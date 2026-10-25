"""Nowoczesny motyw (ciemny/jasny) zbudowany wyłącznie na standardowym ttk.

Nie wymaga zewnętrznych bibliotek (sandbox/offline-friendly). Definiuje:
  - Spójne palety kolorów (ciemną i jasną) jako dataklasy
  - Płaskie przyciski z akcentem i stanami hover/pressed
  - Jednolite odstępy (system PAD_XS/SM/MD/LG/XL)
  - Czcionki z bezpiecznym fallbackiem między systemami (Windows/Linux/macOS)
  - Style dla wszystkich widżetów ttk (Treeview, Entry, Button, Scrollbar, itp.)

To właśnie ta warstwa daje aplikacji „profesjonalny feel" zamiast domyślnego,
przestarzałego wyglądu ttk. Zmiana motywu to jedno wywołanie theme.apply("dark").

Użycie:
    theme = Theme(root_window)
    theme.apply("dark")           # lub "light"
    bg_color = theme.palette.bg   # dostęp do kolorów palety
"""

from __future__ import annotations

import tkinter as tk
import tkinter.font as tkfont
from dataclasses import dataclass
from tkinter import ttk

from .icons import icon_font

# Spójny system odstępów (zamiast magicznych liczb rozsianych po kodzie).
PAD_XS = 2    # Minimalny odstęp (np. między ikonkami)
PAD_SM = 5    # Mały (np. wewnątrz przycisków)
PAD_MD = 10   # Średni (np. marginesy paneli)
PAD_LG = 16   # Duży (np. padding dialogów)
PAD_XL = 20   # Bardzo duży (rzadko używany)


@dataclass(frozen=True)
class Palette:
    """Paleta kolorów motywu — wszystkie kolory interfejsu w jednym miejscu.

    frozen=True gwarantuje niemutowalność (nie zmienimy koloru przypadkiem).
    Nową paletę tworzymy jako nowy obiekt, nie modyfikujemy istniejącej.
    """
    name: str              # Identyfikator palety ("dark" / "light")
    bg: str                # Tło główne okna
    surface: str           # Tło paneli, list, pól tekstowych
    surface_alt: str       # Tło alternatywne (naprzemienne wiersze, hover)
    border: str            # Kolor obramowań i separatorów
    text: str              # Główny kolor tekstu
    text_muted: str        # Przygaszony tekst (podpowiedzi, etykiety)
    accent: str            # Kolor akcentu (przyciski główne, zaznaczenie aktywne)
    accent_active: str     # Akcent przy hover/pressed
    accent_text: str       # Tekst NA tle akcentu (biały na niebieskim)
    selection: str         # Tło zaznaczenia w listach/drzewach
    selection_text: str    # Tekst zaznaczonych elementów
    danger: str            # Kolor ostrzeżeń/błędów (czerwony)
    success: str           # Kolor sukcesu (zielony)


# Kierunek wizualny "Midnight & Cyan" (makeover): neutralne, nisko-nasycone
# grafity zinc jako ciało aplikacji + JEDEN ciepły akcent amber — pierwszy
# akcent brandowy (nawiązanie do chomika) zamiast generycznego błękitu.
# Akcent używany oszczędnie: akcja główna, zaznaczenie, fokus, progres.
# Kontrasty dobrane wg WCAG: text/surface > 12:1, accent_text/accent > 7:1.
DARK = Palette(
    name="dark",
    bg="#131316",          # zinc-950 — tło okna
    surface="#1b1b1f",     # zinc-900/950 — panele, listy
    surface_alt="#26262b", # zinc-800 — hover, wiersze naprzemienne, toolbary
    border="#2e2e33",      # zinc-700 — separators
    text="#f4f4f5",        # zinc-100
    text_muted="#a1a1aa",  # zinc-400
    accent="#22d3ee",      # amber-500 — akcja główna, fokus, progres
    accent_active="#0891b2",  # amber-600 — hover/pressed
    accent_text="#201a09",    # near-black na amber (kontrast ~8:1)
    selection="#422f10",      # amber-tinted selection bg
    selection_text="#fde68a", # amber-200
    danger="#ef4444",
    success="#22c55e",
)

LIGHT = Palette(
    name="light",
    bg="#fafafa",          # zinc-50
    surface="#ffffff",
    surface_alt="#f4f4f5", # zinc-100
    border="#e4e4e7",      # zinc-200
    text="#18181b",        # zinc-900
    text_muted="#71717a",  # zinc-500
    accent="#0891b2",      # amber-600 (na jasnym tle wymaga ciemniejszego)
    accent_active="#b45309",  # amber-700
    accent_text="#ffffff",
    selection="#fef3c7",      # amber-100
    selection_text="#92400e", # amber-900
    danger="#dc2626",
    success="#16a34a",
)

PALETTES = {"dark": DARK, "light": LIGHT}


def _pick_font(root: tk.Misc, candidates: list[str], size: int, **kw) -> tkfont.Font:
    """Wybiera pierwszą dostępną czcionkę z listy (fallback dla różnych OS)."""
    available = set(tkfont.families(root))
    family = next((c for c in candidates if c in available), candidates[-1])
    return tkfont.Font(root=root, family=family, size=size, **kw)


class Theme:
    """Stosuje paletę do ttk.Style i udostępnia kolory/czcionki widżetom."""

    UI_FONTS = ["Segoe UI", "Inter", "Helvetica Neue", "Noto Sans", "DejaVu Sans", "TkDefaultFont"]
    MONO_FONTS = ["Cascadia Code", "Consolas", "JetBrains Mono", "DejaVu Sans Mono", "TkFixedFont"]

    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.style = ttk.Style(root)
        self.palette: Palette = DARK
        # Klasyczne (nie-ttk) widgety tk nie czytają ttk.Style — rejestrujemy
        # je, żeby Theme.apply() mógł je przemalować przy zmianie motywu
        # (wcześniej Listbox ulubionych / Text logu zostawały w starym kolorze).
        self._tk_widgets: list[tuple[tk.Widget, str]] = []
        # Hierarchia typograficzna: font_sm (dane/status, przygaszone liczniki)
        # → font (UI) → font_title (nagłówki okien). Mono bez zmian.
        self.font = _pick_font(root, self.UI_FONTS, 10)
        self.font_sm = _pick_font(root, self.UI_FONTS, 9)
        self.font_bold = _pick_font(root, self.UI_FONTS, 10, weight="bold")
        self.font_title = _pick_font(root, self.UI_FONTS, 13, weight="bold")
        self.font_mono = _pick_font(root, self.MONO_FONTS, 10)
        # Czcionka glifów ikonowych (Segoe Fluent/MDL2) albo None na systemach
        # bez niej — wtedy toolbar leci w tekstowym fallbacku. Trzymana TUTAJ,
        # bo apply() nadaje ją stylom przy każdej zmianie motywu (i bo obiekt
        # Font musi mieć żywą referencję, inaczej Tcl usuwa nazwany font).
        self.font_icon: tkfont.Font | None = icon_font(root, 13)

    def apply(self, palette_name: str = "dark") -> None:
        p = PALETTES.get(palette_name, DARK)
        self.palette = p
        style = self.style

        try:
            style.theme_use("clam")  # 'clam' najlepiej przyjmuje kolory
        except tk.TclError:
            pass

        self.root.configure(bg=p.bg)

        style.configure(".", background=p.bg, foreground=p.text,
                        fieldbackground=p.surface, font=self.font, borderwidth=0)
        style.configure("TFrame", background=p.bg)
        style.configure("Surface.TFrame", background=p.surface)
        style.configure("Toolbar.TFrame", background=p.surface)
        style.configure("TLabel", background=p.bg, foreground=p.text)
        style.configure("Muted.TLabel", background=p.bg, foreground=p.text_muted,
                        font=self.font_sm)
        style.configure("Title.TLabel", background=p.bg, foreground=p.text, font=self.font_title)
        style.configure("Status.TLabel", background=p.surface, foreground=p.text_muted,
                        font=self.font_sm)

        # Przyciski - płaskie, z hover.
        style.configure("TButton", background=p.surface_alt, foreground=p.text,
                        padding=(PAD_LG, PAD_SM + 2), relief="flat", borderwidth=0)
        style.map("TButton",
                  background=[("active", p.border), ("pressed", p.accent)],
                  foreground=[("disabled", p.text_muted), ("pressed", p.accent_text)])

        style.configure("Title.TLabel", font=self.font_bold, foreground=p.text,
                        background=p.bg, padding=(0, PAD_SM))
        style.configure("TMenubutton", background=p.surface_alt, foreground=p.text,
                        padding=(PAD_MD, PAD_SM + 2), relief="flat")
        style.map("TMenubutton", background=[("active", p.border)])

        # Przycisk akcentowany (główne akcje).
        style.configure("Accent.TButton", background=p.accent, foreground=p.accent_text,
                        padding=(PAD_XL, PAD_SM + 2), relief="flat")
        style.map("Accent.TButton",
                  background=[("active", p.accent_active), ("pressed", p.accent_active)])

        # Mały przycisk paska narzędzi/nawigacji.
        style.configure("Tool.TButton", background=p.surface, foreground=p.text,
                        padding=(PAD_MD, PAD_SM + 1), relief="flat")
        style.map("Tool.TButton",
                  background=[("active", p.surface_alt), ("pressed", p.border)])

        # Breadcrumb - wygląda jak link.
        style.configure("Breadcrumb.TButton", background=p.surface, foreground=p.accent,
                        padding=(PAD_XS, 0), relief="flat", font=self.font)
        style.map("Breadcrumb.TButton", foreground=[("active", p.accent_active)],
                  background=[("active", p.surface)])

        # Entry / pola tekstowe.
        style.configure("TEntry", fieldbackground=p.surface, foreground=p.text,
                        insertcolor=p.text, borderwidth=1, relief="flat", padding=PAD_SM)
        style.map("TEntry", bordercolor=[("focus", p.accent)])

        # Treeview (drzewo + listy plików).
        row_h = self.font.metrics("linespace") + 16
        style.configure("Treeview", background=p.surface, fieldbackground=p.surface,
                        foreground=p.text, borderwidth=0, rowheight=row_h)
        style.map("Treeview",
                  background=[("selected", p.selection)],
                  foreground=[("selected", p.selection_text)])
        style.configure("Treeview.Heading", background=p.surface_alt, foreground=p.text,
                        relief="flat", padding=(PAD_SM, PAD_SM), font=self.font_bold)
        style.map("Treeview.Heading", background=[("active", p.border)])

        # Notebook (zakładki podglądu).
        style.configure("TNotebook", background=p.bg, borderwidth=0)
        style.configure("TNotebook.Tab", background=p.surface_alt, foreground=p.text_muted,
                        padding=(PAD_LG, PAD_SM), borderwidth=0)
        style.map("TNotebook.Tab",
                  background=[("selected", p.surface)],
                  foreground=[("selected", p.text)])

        # Scrollbary — ciemne i cienkie (nowoczesny wygląd).
        style.configure("TScrollbar", background=p.surface_alt, troughcolor=p.surface,
                        borderwidth=0, arrowcolor=p.text_muted, width=10)
        style.map("TScrollbar", background=[("active", p.border)])
        style.configure("TSeparator", background=p.border)
        style.configure("TProgressbar", background=p.accent, troughcolor=p.surface_alt,
                        borderwidth=0, thickness=6)
        style.configure("TCheckbutton", background=p.bg, foreground=p.text)
        style.map("TCheckbutton", background=[("active", p.bg)])
        style.configure("TRadiobutton", background=p.bg, foreground=p.text)
        style.map("TRadiobutton", background=[("active", p.bg)])
        style.configure("TLabelframe", background=p.bg, foreground=p.text_muted,
                        borderwidth=1, relief="solid", bordercolor=p.border)
        style.configure("TLabelframe.Label", background=p.bg, foreground=p.text_muted)
        style.configure("TPanedwindow", background=p.bg)

        # Ikonowe przyciski toolbaru — glify z czcionki systemowej. Font MUSI
        # iść jako obiekt Font (patrz icons.icon_font): string "Segoe MDL2
        # Assets 13" jest niepoprawnym opisem fontu Tk i przycisk zostaje pusty.
        # width=0 — bez tego clam trzyma minimum ~11 znaków i przycisk z jednym
        # glifem rozlewa się na ~100 px (toolbar nie mieścił wszystkich akcji).
        icon_kw = {"font": self.font_icon} if self.font_icon is not None else {}
        style.configure("IconTool.TButton", background=p.surface_alt, foreground=p.text,
                        padding=(10, 7), relief="flat", width=0, **icon_kw)
        style.map("IconTool.TButton",
                  background=[("active", p.border), ("pressed", p.accent)],
                  foreground=[("pressed", p.accent_text)])
        style.configure("IconAccent.TButton", background=p.accent, foreground=p.accent_text,
                        padding=(12, 7), relief="flat", width=0, **icon_kw)
        style.map("IconAccent.TButton",
                  background=[("active", p.accent_active), ("pressed", p.accent_active)])

        # Style komórek miniaturek (zwykła / zaznaczona).
        style.configure("Thumb.TFrame", background=p.surface)
        style.configure("ThumbSelected.TFrame", background=p.selection)

        # Przemaluj zarejestrowane klasyczne widgety (patrz configure_tk_widget).
        for widget, kind in list(self._tk_widgets):
            if not widget.winfo_exists():
                continue
            self._paint_tk_widget(widget, kind)

    def configure_tk_widget(self, widget: tk.Widget, kind: str = "surface") -> None:
        """Koloruje klasyczne (nie-ttk) widżety tk: Text, Canvas, Listbox, Menu.

        Jednocześnie REJESTRUJE widget — przy zmianie motywu Theme.apply()
        przemaluje go automatycznie (martwy punkt sprzed makeoveru)."""
        self._tk_widgets.append((widget, kind))
        self._paint_tk_widget(widget, kind)

    def _paint_tk_widget(self, widget: tk.Widget, kind: str) -> None:
        p = self.palette
        bg = p.surface if kind == "surface" else p.bg
        try:
            widget.configure(bg=bg, fg=p.text, highlightthickness=0)  # type: ignore[call-arg]
        except tk.TclError:
            try:
                widget.configure(bg=bg, highlightthickness=0)  # type: ignore[call-arg]
            except tk.TclError:
                pass


__all__ = ["Theme", "Palette", "PALETTES", "DARK", "LIGHT",
           "PAD_XS", "PAD_SM", "PAD_MD", "PAD_LG", "PAD_XL"]
