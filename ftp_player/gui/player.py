"""Wbudowany odtwarzacz wideo (mpv) osadzony w oknie Tkinter.

Używa biblioteki python-mpv (ctypes wrapper nad libmpv) do osadzenia
pełnowartościowego odtwarzacza w ramce Tkinter. Obsługuje:
  - Wszystkie formaty (MKV, AVI, HEVC, AC3, DTS, napisy ASS/SRT)
  - Streaming z lokalnego proxy (seekowanie, auto-odnowienie tokenu)
  - Nawigację: Następny/Poprzedni plik w folderze
  - Seekbar + play/pause + volume
  - Fullscreen (F11 / podwójny klik)
  - Napisy (automatyczne wykrywanie)

Wymagania:
  - pip install mpv
  - mpv/libmpv dostępne w systemie:
    * Windows: mpv-2.dll w PATH lub obok skryptu
    * Linux: libmpv.so (apt install libmpv-dev)
    * macOS: brew install mpv

Fallback: jeśli mpv niedostępne, otwiera w zewnętrznym VLC (dotychczasowe
zachowanie). Użytkownik widzi komunikat z instrukcją instalacji.
"""

from __future__ import annotations

from tkinter import messagebox, ttk
from typing import Callable, Optional

import tkinter as tk

from ..core.logging_setup import get_logger
from ..core.network_traffic import APP_TRAFFIC, update_native_traffic
from ..core.models import MediaFile
from ..services.network_usage import format_network_rates
from .formatting import human_size
from .theme import PAD_MD, PAD_SM, Theme
from .player_options import PlayerOptions

logger = get_logger("player")
MAX_VOLUME = 150

# Cache dostępności mpv: test tworzy i ubija CAŁĄ instancję mpv (DLL load,
# 100-300 ms) — bez cache'a płaciliśmy to przy KAŻDYM play(). Wynik pozytywny
# cache'owany na stałe; negatywny sprawdzany ponownie (po doinstalowaniu mpv
# w tej samej sesji, np. przez _offer_mpv_download, zacznie działać).
_MPV_AVAILABLE: Optional[bool] = None


def mpv_available() -> bool:
    """Sprawdza czy python-mpv i libmpv są dostępne (cache'owane po sukcesie)."""
    global _MPV_AVAILABLE
    if _MPV_AVAILABLE:
        return True
    try:
        # Na Windows: upewnij się że libmpv-2.dll jest w PATH
        from ..core.mpv_bootstrap import ensure_libmpv_in_path
        ensure_libmpv_in_path()

        import mpv  # type: ignore[import]
        # Próba utworzenia instancji (waliduje czy .dll/.so jest ładowalne)
        test = mpv.MPV(vo="null", ao="null")
        test.terminate()
        _MPV_AVAILABLE = True
        return True
    except Exception:
        return False

# Architektura bufora demuxera mpv (po dwóch regresjach — dokumentacja krwi):
#
#  1. NIGDY nie mutujemy limitów W TRAKCIE odtwarzania/pauzy. Zmiana
#     demuxer_max_bytes/readahead_secs na żywo każe mpv ściąć kolejkę
#     demuxera; wycięte dane są stracone, a na strumieniu z seekable=0 mpv
#     nie może ich doszukać → ciągłe przycinanie wideo.
#  2. Bufor mostkuje segmenty ~40 MB, w których chomikuj zrywa połączenie —
#     potrzebny jest zapas CZASU (sekundy materiału), nie sztywna liczba MB.
#  3. Auto-dopasowanie do bitrate: przy file-loaded (kolejka demuxera jest
#     wtedy niemal PUSTA — ustawienie limitu nic nie ścina) liczmy bitrate
#     = rozmiar pliku ÷ czas trwania i ustawiamy bufor na BUFFER_TARGET_SECS
#     sekund materiału, w clamp [BUFFER_MIN, ACTIVE_MAX_BYTES]. Efekt:
#      - film 4 Mbps → bufor ~45 MiB (marnowanie transferu przy pauzie spada
#        ~10× względem sztywnego 512 MiB), cushion dalej 90 s,
#      - 4K 50 Mbps → pełne 512 MiB (jak oryginalnie),
#      - brak znanego czasu trwania/rozmiaru → zostają limity domyślne.
#  4. Pauza: nic nie robimy — pobieranie samo zamiera po osiągnięciu limitu,
#     który teraz jest dopasowany do pliku.
ACTIVE_READAHEAD_SECS = 120
ACTIVE_MAX_BYTES = 512 * 1024 * 1024
BUFFER_TARGET_SECS = 90                    # ile sekund materiału ma pomieścić bufor
BUFFER_MIN_BYTES = 32 * 1024 * 1024        # podłoga: mostek na ~40 MB segmenty
# strop = ACTIVE_MAX_BYTES (nigdy więcej niż oryginalny, sprawdzony limit)


def compute_buffer_bytes(size: int, duration: float) -> Optional[int]:
    """Zwraca docelowy rozmiar bufora (bajty) wg bitrate pliku, lub None.

    Bitrate liczony jako średnia: rozmiar ÷ czas trwania (chwilowy bitrate
    w scenach akcji bywa 2-3× wyższy, ale bufor i tak trzyma CAŁOŚCI średnio).
    None = brak danych do policzenia (nie tunujemy, zostają limity domyślne).
    """
    if not size or size <= 0 or not duration or duration <= 0:
        return None
    bitrate = size / duration  # bajty na sekundę materiału
    return int(min(max(bitrate * BUFFER_TARGET_SECS, BUFFER_MIN_BYTES),
                   ACTIVE_MAX_BYTES))


def _format_time(seconds: float) -> str:
    """Formatuje sekundy na HH:MM:SS lub MM:SS."""
    if seconds is None or seconds < 0:
        return "--:--"
    s = int(seconds)
    h, m, sec = s // 3600, (s % 3600) // 60, s % 60
    if h > 0:
        return f"{h}:{m:02d}:{sec:02d}"
    return f"{m:02d}:{sec:02d}"


class VideoPlayer(PlayerOptions, tk.Toplevel):
    """Okno odtwarzacza wideo z osadzonym mpv.

    Argumenty:
        parent       - okno rodzic (App)
        file         - plik do odtworzenia (MediaFile)
        playlist     - lista plików wideo w folderze (do nawigacji prev/next)
        stream_url   - URL strumienia (z proxy lub bezpośredni)
        theme        - motyw kolorystyczny
        on_close     - callback przy zamknięciu
    """

    def __init__(
        self,
        parent,
        file: MediaFile,
        playlist: list[MediaFile],
        stream_url: str,
        theme: Theme,
        on_close: Optional[Callable] = None,
    ):
        super().__init__(parent)
        self.title(f"▶ {file.name}")
        self.geometry("1200x720")
        self.minsize(640, 400)

        # Start zmaksymalizowany (z config)
        self._app_config = getattr(parent, "config_obj", None)
        if self._app_config and self._app_config.get("player_maximized", True):
            self.after(10, lambda: self.wm_state("zoomed"))

        self._file = file
        self._playlist = playlist
        self._stream_url = stream_url
        self._theme = theme
        self._on_close = on_close
        # Callback dostarczania świeżego URL (zwykle monkey-patch z open_player,
        # ale MUSI być zainicjalizowany — bez tego end-file/token-refresh
        # cicho nie działał, bo AttributeError połykał blanket-except).
        self._on_get_stream_url: Optional[Callable[[MediaFile], str]] = None
        self._app_tasks = getattr(parent, "tasks", None)  # run_async dla URL-i
        # Playlist O(1): lista zbudowana raz per playlista, potem tylko 2 wiersze
        self._pl_built_for: Optional[list] = None
        self._pl_marked_index: Optional[int] = None
        self._mpv = None
        self._fullscreen = False
        self._seeking = False
        self._update_id: Optional[str] = None

        # Znajdź indeks bieżącego pliku w playliście
        self._index = 0
        for i, f in enumerate(playlist):
            if f.id == file.id:
                self._index = i
                break

        self._build_ui()
        self._bind_keys()
        self.protocol("WM_DELETE_WINDOW", self._close)
        self._controls_visible = True
        self._hide_controls_id: Optional[str] = None

        # Uruchom mpv po zbudowaniu UI (potrzebny winfo_id)
        self.after(100, self._start_mpv)

    def _build_ui(self) -> None:

        # Główny kontener: [wideo | playlist]
        self._main_pane = ttk.Frame(self)
        self._main_pane.pack(fill="both", expand=True)

        # Ramka na wideo (mpv renderuje tutaj)
        self._video_frame = tk.Frame(self._main_pane, bg="black")
        self._video_frame.pack(side="left", fill="both", expand=True)

        # Panel playlist (prawy bok)
        self._playlist_frame = ttk.Frame(self._main_pane, style="Surface.TFrame", width=220)
        self._playlist_frame.pack(side="right", fill="y")
        self._playlist_frame.pack_propagate(False)
        ttk.Label(self._playlist_frame, text="Playlista", style="Muted.TLabel").pack(
            padx=PAD_SM, pady=(PAD_SM, 0))
        self._playlist_box = tk.Listbox(self._playlist_frame, borderwidth=0,
                                         highlightthickness=0, font=("", 9),
                                         selectmode="single")
        self._theme.configure_tk_widget(self._playlist_box, "surface")
        self._playlist_box.pack(fill="both", expand=True, padx=2, pady=2)
        for i, f in enumerate(self._playlist):
            prefix = "▶ " if i == self._index else "  "
            self._playlist_box.insert("end", f"{prefix}{f.name}")
        if self._playlist:
            self._playlist_box.selection_set(self._index)
            self._playlist_box.see(self._index)
        self._playlist_box.bind("<Double-Button-1>", self._on_playlist_dblclick)

        # Panel kontrolek (dół)
        self._controls = ttk.Frame(self, style="Toolbar.TFrame")
        self._controls.pack(fill="x", side="bottom")

        # Seekbar
        seekbar_frame = ttk.Frame(self._controls)
        seekbar_frame.pack(fill="x", padx=PAD_MD, pady=(PAD_SM, 0))

        self._time_var = tk.StringVar(value="--:-- / --:--")
        ttk.Label(seekbar_frame, textvariable=self._time_var,
                  style="Muted.TLabel", width=18).pack(side="right")

        self._seek_var = tk.DoubleVar(value=0)
        self._seekbar = ttk.Scale(seekbar_frame, from_=0, to=100,
                                  orient="horizontal", variable=self._seek_var,
                                  command=self._on_seek)
        self._seekbar.pack(fill="x", expand=True, side="left")
        # Klik na seekbar → seekuj bezpośrednio do pozycji kursora
        self._seekbar.bind("<Button-1>", self._on_seekbar_click)

        # Przyciski
        btn_frame = ttk.Frame(self._controls)
        btn_frame.pack(fill="x", padx=PAD_MD, pady=PAD_SM)

        ttk.Button(btn_frame, text="⏮ Poprzedni", style="Tool.TButton",
                   command=self._prev).pack(side="left", padx=2)
        self._play_btn = ttk.Button(btn_frame, text="⏸ Pauza", style="Tool.TButton",
                                    command=self._toggle_pause)
        self._play_btn.pack(side="left", padx=2)
        ttk.Button(btn_frame, text="⏭ Następny", style="Tool.TButton",
                   command=self._next).pack(side="left", padx=2)

        ttk.Separator(btn_frame, orient="vertical").pack(side="left", fill="y", padx=PAD_MD)

        ttk.Button(btn_frame, text="⏪ -10s", style="Tool.TButton",
                   command=lambda: self._seek_relative(-10)).pack(side="left", padx=2)
        ttk.Button(btn_frame, text="⏩ +10s", style="Tool.TButton",
                   command=lambda: self._seek_relative(10)).pack(side="left", padx=2)

        ttk.Separator(btn_frame, orient="vertical").pack(side="left", fill="y", padx=PAD_MD)

        # Volume (z config)
        ttk.Label(btn_frame, text="🔊", style="Muted.TLabel").pack(side="left")
        saved_vol = int(self._app_config.get("player_volume", 100)) if self._app_config else 100
        saved_vol = max(0, min(MAX_VOLUME, saved_vol))
        self._vol_var = tk.IntVar(value=saved_vol)
        vol_scale = ttk.Scale(btn_frame, from_=0, to=MAX_VOLUME, orient="horizontal",
                              variable=self._vol_var, length=100,
                              command=self._on_volume)
        vol_scale.pack(side="left", padx=PAD_SM)

        ttk.Separator(btn_frame, orient="vertical").pack(side="left", fill="y", padx=PAD_MD)

        ttk.Button(btn_frame, text="⛶ Fullscreen (F11)", style="Tool.TButton",
                   command=self._toggle_fullscreen).pack(side="left", padx=2)

        # Info: nazwa pliku + pozycja w playliście
        self._info_var = tk.StringVar(
            value=f"{self._file.name}  ({self._index + 1}/{len(self._playlist)})")
        ttk.Label(btn_frame, textvariable=self._info_var,
                  style="Muted.TLabel").pack(side="right", padx=PAD_MD)
        self._build_options()
        # Wspólny monitor z okna aplikacji: bez kolejnego wątku i pomiaru ruchu.
        self._network_monitor = getattr(self._root(), "_network_monitor", None)
        self._network_var = tk.StringVar(master=self)
        self._network_label = ttk.Label(self._controls, textvariable=self._network_var,
                                         style="Muted.TLabel", anchor="e")
        self._network_label.pack(fill="x", padx=PAD_MD, pady=(0, PAD_SM))
        self._network_tick_id = None
        self.bind("<Destroy>", self._stop_network_updates, add="+")
        self._update_network_usage()

    def _update_network_usage(self) -> None:
        """Display shared application and PC transfer rates once per second."""
        monitor = self._network_monitor
        app_rates = monitor.app_rates if monitor is not None else None
        pc_rates = monitor.rates if monitor is not None else None
        self._network_var.set(
            f"{format_network_rates(app_rates, 'FTP Player', estimated=True)}    |    "
            f"{format_network_rates(pc_rates)}")
        self._network_tick_id = self.after(1000, self._update_network_usage)

    def _stop_network_updates(self, event) -> None:
        """Cancel this window's timer while leaving the shared monitor running."""
        if event.widget is self and self._network_tick_id is not None:
            self.after_cancel(self._network_tick_id)
            self._network_tick_id = None

    def _bind_keys(self) -> None:
        self.bind("<space>", lambda _: self._toggle_pause())
        self.bind("<Left>", lambda _: self._seek_relative(-5))
        self.bind("<Right>", lambda _: self._seek_relative(5))
        self.bind("<Control-Left>", lambda _: self._seek_relative(-30))
        self.bind("<Control-Right>", lambda _: self._seek_relative(30))
        self.bind("<F11>", lambda _: self._toggle_fullscreen())
        self.bind("<Escape>", lambda _: self._exit_fullscreen())
        self.bind("<n>", lambda _: self._next())
        self.bind("<p>", lambda _: self._prev())
        self.bind("<m>", lambda _: self._toggle_mute())
        self.bind("<s>", lambda _: self._save_frame("subtitles"))
        self.bind("<period>", lambda _: self._player_command("frame-step"))
        self.bind("<comma>", lambda _: self._player_command("frame-back-step"))
        # Dwuklik na WIDEO (nie na playlist) = fullscreen
        self._video_frame.bind("<Double-Button-1>", self._on_video_dblclick)
        # Klik na wideo = pauza (z debounce żeby nie łapał pierwszego klika z dwuklika)
        self._click_after_id: Optional[str] = None
        self._video_frame.bind("<Button-1>", self._on_video_click)
        # Auto-hide kontrolek przy braku ruchu myszy
        self.bind("<Motion>", self._on_mouse_motion)
        self._video_frame.bind("<Motion>", self._on_mouse_motion)

    def _on_mouse_motion(self, _event=None) -> None:
        """Pokazuje kontrolki i resetuje timer auto-hide."""
        if not self._controls_visible:
            self._show_controls()
        # Reset timera
        if self._hide_controls_id:
            self.after_cancel(self._hide_controls_id)
        self._hide_controls_id = self.after(3000, self._hide_controls)

    def _on_video_click(self, _event=None) -> None:
        """Klik na wideo = pauza (z debounce 300ms, żeby dwuklik nie pausował)."""
        if self._click_after_id:
            self.after_cancel(self._click_after_id)
        self._click_after_id = self.after(300, self._do_pause_click)

    def _on_video_dblclick(self, _event=None) -> None:
        """Dwuklik na wideo = fullscreen (anuluje oczekującą pauzę)."""
        if self._click_after_id:
            self.after_cancel(self._click_after_id)
            self._click_after_id = None
        self._toggle_fullscreen()

    def _do_pause_click(self) -> None:
        self._click_after_id = None
        self._toggle_pause()

    def _hide_controls(self) -> None:
        """Ukrywa kontrolki i playlistę po 3s braku aktywności (tylko w fullscreen)."""
        if not self._fullscreen:
            return
        self._controls_visible = False
        self._controls.pack_forget()
        self._playlist_frame.pack_forget()
        self.config(cursor="none")

    def _show_controls(self) -> None:
        """Przywraca widoczność kontrolek i playlisty."""
        if self._controls_visible:
            return
        self._controls_visible = True
        self._playlist_frame.pack(side="right", fill="y", in_=self._main_pane)
        self._controls.pack(fill="x", side="bottom")
        self.config(cursor="")

    # ------------------------------------------------------------------ #
    # MPV lifecycle
    # ------------------------------------------------------------------ #
    def _start_mpv(self) -> None:
        """Inicjalizuje mpv i rozpoczyna odtwarzanie."""
        try:
            from ..core.mpv_bootstrap import ensure_libmpv_in_path
            ensure_libmpv_in_path()
            import mpv  # type: ignore[import]
        except (ImportError, OSError) as exc:
            messagebox.showerror(
                "Brak mpv",
                f"Nie udało się załadować mpv:\n{exc}\n\n"
                "Zainstaluj python-mpv i libmpv:\n"
                "  pip install mpv\n"
                "Windows: https://mpv.io/installation/",
                parent=self)
            self._close()
            return

        # Upewnij się że ramka wideo jest w pełni zmapowana (Windows wymaga tego)
        self._video_frame.update_idletasks()
        self.update_idletasks()

        # Pobierz window ID ramki wideo (dla osadzenia mpv)
        wid = str(int(self._video_frame.winfo_id()))
        logger.info("Uruchamiam mpv z wid=%s, url=%s", wid, self._stream_url[:80])

        try:
            self._mpv = mpv.MPV(
                wid=wid,
                vo="gpu,direct3d,sdl",
                hwdec="auto",
                audio_channels="auto-safe",
                audio_spdif="",
                input_default_bindings=False,
                input_vo_keyboard=False,
                osc=False,
                keep_open="yes",
                idle="yes",
                # Suwak pozwala na 150%; domyślny limit mpv wynosi tylko 130%.
                volume_max=MAX_VOLUME,
                # Streaming: HTTP seek przez proxy oraz ograniczony bufor w pamięci
                # do przodu (patrz stałe ACTIVE_* na górze modułu) — bez tego
                # pauza pobiera setki MB zanim demuxer się zatrzyma.
                cache="yes",
                force_seekable="yes",
                # Proxy obsługuje Range: demuxer może pobrać indeks z końca pliku.
                cache_pause_initial="yes",
                cache_pause_wait=3,
                demuxer_max_back_bytes=32 * 1024 * 1024,
                demuxer_max_bytes=ACTIVE_MAX_BYTES,
                demuxer_readahead_secs=ACTIVE_READAHEAD_SECS,
                log_handler=self._mpv_log_handler,
                loglevel="warn",
            )
            self._mpv.volume = self._vol_var.get()

            # Obserwuj zdarzenia mpv
            @self._mpv.property_observer("pause")
            def _on_pause(_name, value):
                try:
                    self._schedule_player(lambda: self._on_pause_changed(bool(value)))
                except tk.TclError:
                    pass

            # Obserwuj end-file (wygaśnięcie tokenu → auto-refresh URL)
            @self._mpv.event_callback("end-file")
            def _on_end_file(event):
                try:
                    reason = event.get("event", {}).get("reason", "")
                    if reason == "error" and self._on_get_stream_url:
                        # Token wygasł — odśwież URL i wznów
                        logger.info("Player: end-file error — odświeżam URL")
                        self._schedule_player(self._refresh_and_resume)
                except Exception:
                    pass

            # Auto-dopasowanie bufora do bitrate pliku — JEDNORAZOWO przy
            # załadowaniu (kolejka demuxera niemal pusta → nic nie ścinamy).
            # Patrz komentarz "Architektura bufora" przy stałych na górze.
            @self._mpv.event_callback("file-loaded")
            def _on_file_loaded(_event):
                try:
                    self._schedule_player(self._tune_demuxer_buffer)
                except tk.TclError:
                    pass  # okno zniszczone

            # Rozpocznij odtwarzanie
            self._mpv.play(self._stream_url)

            # Resume: seekuj do zapamiętanej pozycji (jeśli istnieje)
            resume_pos = _resume_positions.get(self._file.id)
            if resume_pos and resume_pos > 5:
                # Poczekaj chwilę aż mpv załaduje plik, potem seekuj
                def _do_resume():
                    try:
                        if self._mpv and self._mpv.duration:
                            self._mpv.seek(resume_pos, "absolute")
                            logger.info("Resume od %.0fs dla %s", resume_pos, self._file.name)
                    except Exception:
                        pass
                self.after(1500, _do_resume)

            # Uruchom pętlę aktualizacji seekbara
            self._start_update_loop()

        except Exception as exc:
            logger.error("Nie udało się uruchomić mpv: %s", exc)
            messagebox.showerror("Błąd mpv", str(exc), parent=self)
            self._close()

    @staticmethod
    def _mpv_log_handler(loglevel, component, message):
        """Przekierowuje logi mpv do loggera aplikacji."""
        if loglevel in ("error", "fatal"):
            logger.error("mpv [%s] %s", component, message)
        elif loglevel == "warn":
            logger.warning("mpv [%s] %s", component, message)

    def _refresh_and_resume(self) -> None:
        """Odnawia URL i wznawia odtwarzanie (po wygaśnięciu tokenu).

        URL pobierany w tle — get_fresh_download_url to SOAP+HTTP i mroziłoby
        wątek UI (wywoływane także z observera end-file)."""
        if not self._mpv or not self._on_get_stream_url:
            return
        try:
            pos = self._mpv.time_pos or 0
        except Exception:
            pos = 0
        target = self._file

        def work():
            return self._on_get_stream_url(target) or ""

        def on_done(new_url: str) -> None:
            if not new_url or self._mpv is None or self._file is not target:
                return
            self._stream_url = new_url
            self._mpv.play(new_url)
            if pos > 5:
                self.after(1500, lambda: self._safe_seek(pos))
            self._time_var.set("🔄 Odnawianie strumienia…")
            logger.info("Token odnowiony, wznawiam od %.0fs", pos)

        self._run_url_task(work, on_done)

    def _run_url_task(self, work, on_done) -> None:
        """Uruchamia pobranie URL w tle (z fallbackiem synchronicznym)."""
        if self._app_tasks is not None:
            self._app_tasks.run_async(work, on_success=on_done)
        else:
            on_done(work())

    def _safe_seek(self, pos: float) -> None:
        try:
            if self._mpv and self._mpv.duration:
                self._mpv.seek(pos, "absolute")
        except Exception:
            pass

    def _start_update_loop(self) -> None:
        """Aktualizuje seekbar i czas co 250ms."""
        if self._mpv is None:
            return
        try:
            pos = self._mpv.time_pos
            dur = self._mpv.duration
            if pos is not None and dur and dur > 0 and not self._seeking:
                self._seek_var.set(pos / dur * 100)
                buffered = self._mpv.demuxer_cache_duration or 0
                state = "Buforowanie" if self._mpv.paused_for_cache else "Bufor"
                self._time_var.set(
                    f"{_format_time(pos)} / {_format_time(dur)}  ·  {state}: {buffered:.0f} s")
        except Exception:
            pass
        update_native_traffic(self, self._mpv, self._stream_url)
        self._update_id = self.after(250, self._start_update_loop)

    # ------------------------------------------------------------------ #
    # Kontrolki
    # ------------------------------------------------------------------ #
    def _toggle_pause(self) -> None:
        if self._mpv:
            self._mpv.pause = not self._mpv.pause

    def _on_pause_changed(self, paused: bool) -> None:
        """Reakcja na pauzę/wznowienie — wyłącznie UI (etykieta przycisku).

        CELOWO nic nie robimy z buforem demuxera: zmiana limitów w runtime
        każe mpv ściąć kolejkę demuxera, a wycięte dane są stracone — na
        strumieniu z seekable=0 mpv nie może ich doszukać i czeka na liniowe
        dotełanie (objaw: ciągłe przycinanie wideo). Pauza i tak przerywa
        pobieranie sama z siebie po osiągnięciu limitu bufora.
        """
        try:
            self._play_btn.configure(text="▶ Play" if paused else "⏸ Pauza")
        except tk.TclError:
            pass

    def _tune_demuxer_buffer(self) -> None:
        """Jednorazowe dopasowanie bufora do bitrate pliku (event file-loaded).

        Wywoływane, gdy mpv zna już duration, a kolejka demuxera jest niemal
        pusta — ustawienie limitu niczego nie ścina (to NIE jest mutacja
        w trakcie odtwarzania). Powtórne wywołania (replay po odświeżeniu
        tokenu) ustawiają te same wartości — idempotentne.
        """
        if self._mpv is None:
            return
        try:
            duration = self._mpv.duration or 0
        except Exception:  # noqa: BLE001
            duration = 0
        size = getattr(self._file, "size", 0) or 0
        target = compute_buffer_bytes(size, duration)
        if target is None:
            return  # brak duration/size — zostają bezpieczne limity domyślne
        try:
            self._mpv.demuxer_max_bytes = target
            bitrate_mbps = (size / duration) * 8 / 1_000_000
            logger.info("Bufor dopasowany do bitrate: %.1f Mbps → %s (%d s materiału)",
                        bitrate_mbps, human_size(target), BUFFER_TARGET_SECS)
        except Exception as exc:  # noqa: BLE001 — starsze libmpv może odmówić
            logger.debug("Nie udało się dopasować bufora do bitrate: %s", exc)

    def _on_seek(self, value) -> None:
        """Callback z seekbara — seek do pozycji."""
        if self._mpv and self._mpv.duration:
            self._seeking = True
            target = float(value) / 100 * self._mpv.duration
            self._mpv.seek(target, "absolute")
            self.after(500, lambda: setattr(self, "_seeking", False))

    def _on_seekbar_click(self, event) -> None:
        """Klik na seekbar → seekuj bezpośrednio do pozycji kursora (nie skokowo)."""
        widget = event.widget
        # Oblicz pozycję 0-100 na podstawie X kliknięcia
        width = widget.winfo_width()
        if width <= 0:
            return
        x = max(0, min(event.x, width))
        value = (x / width) * 100
        self._seek_var.set(value)
        self._on_seek(value)
        return "break"  # Zapobiega domyślnemu zachowaniu (skokowe)

    def _seek_relative(self, seconds: float) -> None:
        if self._mpv:
            self._mpv.seek(seconds, "relative")

    def _on_volume(self, value) -> None:
        if self._mpv:
            self._mpv.volume = max(0, min(MAX_VOLUME, int(float(value))))

    def _toggle_fullscreen(self) -> None:
        self._fullscreen = not self._fullscreen
        self.attributes("-fullscreen", self._fullscreen)
        if not self._fullscreen:
            # Wyjście z fullscreen — pokaż kontrolki
            self._show_controls()

    def _exit_fullscreen(self) -> None:
        if self._fullscreen:
            self._fullscreen = False
            self.attributes("-fullscreen", False)
            self._show_controls()

    # ------------------------------------------------------------------ #
    # Nawigacja playlist
    # ------------------------------------------------------------------ #
    def _next(self) -> None:
        if not self._playlist:
            return
        self._index = (self._index + 1) % len(self._playlist)
        self._play_index(self._index)

    def _prev(self) -> None:
        if not self._playlist:
            return
        self._index = (self._index - 1) % len(self._playlist)
        self._play_index(self._index)

    def _play_index(self, idx: int) -> None:
        """Przełącza na plik o danym indeksie w playliście (URL w tle)."""
        self._file = self._playlist[idx]
        self._info_var.set(f"{self._file.name}  ({idx + 1}/{len(self._playlist)})")
        self.title(f"▶ {self._file.name}")
        self._time_var.set("⏳ Ładowanie…")
        self._update_playlist_highlight()
        target = self._file

        def work():
            # Świeży URL (token mógł wygasnąć); fallback do URL z cache
            if self._on_get_stream_url:
                return self._on_get_stream_url(target) or (target.url or "")
            return target.url or ""

        def on_done(new_url: str) -> None:
            if not new_url or self._mpv is None or self._file is not target:
                return
            self._stream_url = new_url
            # Numery ścieżek i korekty synchronizacji dotyczą konkretnego pliku.
            for name, value in (("aid", "auto"), ("sid", "auto"),
                                ("audio_delay", 0), ("sub_delay", 0)):
                self._set_option(name, value)
            self._mpv.play(new_url)

        self._run_url_task(work, on_done)

    def _on_playlist_dblclick(self, _event=None) -> None:
        """Podwójne kliknięcie na element playlisty — przełącz na ten plik."""
        sel = self._playlist_box.curselection()
        if sel:
            self._index = sel[0]
            self._play_index(self._index)

    def _update_playlist_highlight(self) -> None:
        """Podświetla bieżący utwór — O(1), bez przebudowy całej listy.

        Wcześniej każdy next/prev robił delete-all + N insertów (playlista
        2000 plików = setki ms zamrożenia okna playera w trakcie odtwarzania).
        Pełne zbudowanie tylko przy zmianie playlisty; potem podmieniamy
        wyłącznie dwa wiersze (stary i nowy bieżący).
        """
        try:
            box = self._playlist_box
            if self._pl_built_for is not self._playlist:
                box.delete(0, "end")
                for f in self._playlist:
                    box.insert("end", f"  {f.name}")
                self._pl_built_for = self._playlist
                self._pl_marked_index = None

            def _set_row(i: int, prefix: str) -> None:
                name = self._playlist[i].name
                box.delete(i)
                box.insert(i, f"{prefix}{name}")

            old = self._pl_marked_index
            if old is not None and old != self._index and 0 <= old < len(self._playlist):
                _set_row(old, "  ")
            if 0 <= self._index < len(self._playlist):
                idx = self._index
                _set_row(idx, "▶ ")
                box.selection_clear(0, "end")
                box.selection_set(idx)
                box.see(idx)
                self._pl_marked_index = idx
        except tk.TclError:
            pass

    # ------------------------------------------------------------------ #
    # Zamknięcie
    # ------------------------------------------------------------------ #
    def _close(self) -> None:
        APP_TRAFFIC.set_native_rate(self, 0)
        self._close_options()
        # Zapamiętaj pozycję (do resume)
        self._save_position()
        for timer in (self._update_id, self._hide_controls_id, self._click_after_id):
            if timer:
                try:
                    self.after_cancel(timer)
                except tk.TclError:
                    pass  # timer już wygasł
        self._update_id = None
        self._hide_controls_id = None
        self._click_after_id = None
        if self._mpv:
            try:
                self._mpv.terminate()
            except Exception:
                pass
            self._mpv = None
        if self._on_close:
            self._on_close()
        self.destroy()

    def _save_position(self) -> None:
        """Zapamiętuje pozycję odtwarzania i głośność."""
        # Zapisz głośność do config
        if self._app_config:
            self._app_config["player_volume"] = self._vol_var.get()
            self._app_config.save()
        if self._mpv is None:
            return
        try:
            pos = self._mpv.time_pos
            dur = self._mpv.duration
            if pos and dur and pos > 5 and pos < dur - 10:
                # Zapisz tylko jeśli nie na początku/końcu
                _resume_positions[self._file.id] = pos
        except Exception:
            pass


# Globalny słownik pozycji resume (per sesja — ginie po zamknięciu app)
_resume_positions: dict[str, float] = {}


def open_player(
    parent,
    file: MediaFile,
    playlist: list[MediaFile],
    stream_url: str,
    theme: Theme,
    get_stream_url: Optional[Callable[[MediaFile], str]] = None,
    on_close: Optional[Callable] = None,
) -> Optional[VideoPlayer]:
    """Otwiera wbudowany player (lub None jeśli mpv niedostępny).

    Argumenty:
        parent         - okno rodzic
        file           - plik do odtworzenia
        playlist       - lista wideo w folderze (nawigacja)
        stream_url     - URL strumienia
        theme          - motyw
        get_stream_url - callback: (MediaFile) → URL strumienia (do nawigacji)
        on_close       - callback przy zamknięciu

    Zwraca:
        VideoPlayer jeśli mpv dostępny, None jeśli nie (caller powinien fallback do VLC).
    """
    if not mpv_available():
        return None
    player = VideoPlayer(parent, file, playlist, stream_url, theme, on_close)
    player._on_get_stream_url = get_stream_url
    return player


__all__ = ["VideoPlayer", "open_player", "mpv_available"]
