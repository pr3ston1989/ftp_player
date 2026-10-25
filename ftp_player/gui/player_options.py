"""Advanced mpv controls and asynchronous video export UI."""

from datetime import datetime
from pathlib import Path
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk

from ..core.system import sanitize_filename
from ..services.video_exports import ExportCancelled, create_contact_sheet


def track_label(track):
    """Describe a track using its stable mpv ID and available metadata."""
    parts = [f"#{track['id']}"]
    for key in ('lang', 'title', 'codec'):
        if track.get(key):
            parts.append(str(track[key]))
    if track.get('audio-channels'):
        parts.append(f"{track['audio-channels']} kanałów")
    if track.get('external'):
        parts.append('zewnętrzna')
    return ' · '.join(parts)


class PlayerOptions:
    """Mixin providing menus without changing the playback transport."""

    def _build_options(self):
        self._option_vars = {}
        self._export_cancel = threading.Event()
        self._export_busy = False
        self._export_events = queue.Queue()
        self._options_closed = False
        self._options_poll_id = self.after(100, self._poll_options)
        row = ttk.Frame(self._controls, padding=(10, 3))
        row.pack(fill='x')
        for title, builder in (
            ('Obraz', self._image_menu), ('Dźwięk', self._audio_menu),
            ('Napisy', self._subtitle_menu), ('Odtwarzanie', self._playback_menu),
            ('Zapis klatek', self._export_menu),
        ):
            menu = tk.Menu(row, tearoff=False)
            self._theme.configure_tk_widget(menu)
            menu.configure(postcommand=lambda m=menu, b=builder: self._fill_options_menu(m, b))
            ttk.Menubutton(row, text=title, menu=menu).pack(side='left', padx=3)
        self._option_status = tk.StringVar()
        status_row = ttk.Frame(self._controls, padding=(10, 0))
        status_row.pack(fill='x')
        self._cancel_export_btn = ttk.Button(status_row, text='Anuluj', command=self._export_cancel.set)
        ttk.Label(status_row, textvariable=self._option_status, style='Muted.TLabel', width=32).pack(
            side='left', fill='x', expand=True)
        self._video_menu = tk.Menu(self._video_frame, tearoff=False)
        self._theme.configure_tk_widget(self._video_menu)
        self._context_menus = []
        for title, builder in [('Obraz', self._image_menu), ('Dźwięk', self._audio_menu),
                               ('Napisy', self._subtitle_menu), ('Odtwarzanie', self._playback_menu),
                               ('Zapis klatek', self._export_menu)]:
            child = tk.Menu(self._video_menu, tearoff=False)
            self._theme.configure_tk_widget(child)
            self._video_menu.add_cascade(label=title, menu=child)
            self._context_menus.append((child, builder))
        self._video_frame.bind('<Button-3>', self._show_video_menu)

    def _fill_options_menu(self, menu, builder):
        menu.delete(0, 'end')
        if self._mpv is None:
            menu.add_command(label='Odtwarzacz jeszcze się ładuje', state='disabled')
            return
        try:
            builder(menu)
        except Exception as exc:
            menu.add_command(label=f'Opcja niedostępna: {exc}', state='disabled')

    def _get_option(self, name, default=None):
        try:
            return getattr(self._mpv, name) if self._mpv is not None else default
        except Exception:
            return default

    def _set_option(self, name, value):
        if self._mpv is None:
            return
        try:
            setattr(self._mpv, name, value)
        except Exception as exc:
            messagebox.showerror('Ustawienie odtwarzacza', str(exc), parent=self)

    def _choice(self, menu, name, choices):
        var = self._option_vars.setdefault(name, tk.StringVar(master=self))
        current = self._get_option(name, 'auto')
        if current is False:
            current = 'no'
        elif current is True:
            current = 'auto'
        selected = str(current)
        for _, value in choices:
            try:
                if abs(float(current) - float(value)) < 0.0001:
                    selected = str(value)
                    break
            except (TypeError, ValueError):
                pass
        var.set(selected)
        for label, value in choices:
            menu.add_radiobutton(label=label, value=str(value), variable=var,
                                 command=lambda n=name, v=value: self._set_option(n, v))

    def _image_menu(self, menu):
        self._choice(menu, 'video_aspect_override', [('Proporcje oryginalne', -1),
                     ('16:9', 16 / 9), ('4:3', 4 / 3), ('21:9', 21 / 9), ('1:1', 1)])
        menu.add_separator()
        menu.add_command(label='Wypełnij okno (przytnij)', command=lambda: self._set_option('panscan', 1))
        menu.add_command(label='Dopasuj cały obraz', command=lambda: self._set_option('panscan', 0))
        self._choice(menu, 'video_rotate', [('Obrót 0°', 0), ('Obrót 90°', 90),
                                           ('Obrót 180°', 180), ('Obrót 270°', 270)])

    def _tracks_menu(self, menu, kind, property_name):
        tracks = [track for track in (self._get_option('track_list', []) or []) if track.get('type') == kind]
        choices = [('Automatycznie', 'auto'), ('Wyłącz', 'no')]
        choices.extend((track_label(track), track['id']) for track in tracks)
        self._choice(menu, property_name, choices)
        if not tracks:
            menu.add_command(label='Brak ścieżek w tym pliku', state='disabled')

    def _audio_menu(self, menu):
        self._tracks_menu(menu, 'audio', 'aid')
        menu.add_separator()
        menu.add_command(label='Wycisz / przywróć  M', command=self._toggle_mute)
        menu.add_command(label='Opóźnienie audio…', command=lambda: self._delay_dialog('audio_delay', 'Opóźnienie dźwięku'))

    def _subtitle_menu(self, menu):
        self._tracks_menu(menu, 'sub', 'sid')
        menu.add_separator()
        menu.add_command(label='Dodaj napisy z pliku…', command=self._load_subtitles)
        menu.add_command(label='Pokaż / ukryj napisy', command=lambda: self._set_option(
            'sub_visibility', not self._get_option('sub_visibility', True)))
        menu.add_command(label='Opóźnienie napisów…', command=lambda: self._delay_dialog('sub_delay', 'Opóźnienie napisów'))
        menu.add_command(label='Większe napisy', command=lambda: self._set_option(
            'sub_scale', min(3, float(self._get_option('sub_scale', 1)) + 0.1)))
        menu.add_command(label='Mniejsze napisy', command=lambda: self._set_option(
            'sub_scale', max(0.5, float(self._get_option('sub_scale', 1)) - 0.1)))

    def _playback_menu(self, menu):
        self._choice(menu, 'speed', [(f'{speed:g}×', speed) for speed in (0.5, 0.75, 1, 1.25, 1.5, 2)])
        menu.add_separator()
        menu.add_command(label='Następna klatka  .', command=lambda: self._player_command('frame-step'))
        menu.add_command(label='Poprzednia klatka  ,', command=lambda: self._player_command('frame-back-step'))
        self._choice(menu, 'loop_file', [('Bez powtarzania', 'no'), ('Powtarzaj film', 'inf')])
        chapters = self._get_option('chapter_list', []) or []
        if chapters:
            menu.add_separator()
            for index, chapter in enumerate(chapters):
                menu.add_command(label=chapter.get('title') or f'Rozdział {index + 1}',
                                 command=lambda i=index: self._set_option('chapter', i))

    def _export_menu(self, menu):
        menu.add_command(label='Zrzut PNG z napisami…  S', command=lambda: self._save_frame('subtitles'))
        menu.add_command(label='Zrzut PNG bez napisów…', command=lambda: self._save_frame('video'))
        menu.add_command(label='Miniaturka bieżącej klatki…', command=self._save_thumbnail)
        menu.add_separator()
        menu.add_command(label='Arkusz miniaturek filmu…', command=self._contact_sheet_dialog,
                         state='disabled' if self._export_busy else 'normal')
        if self._export_busy:
            menu.add_command(label='Anuluj generowanie miniaturek', command=self._export_cancel.set)

    def _show_video_menu(self, event):
        for child, builder in self._context_menus:
            self._fill_options_menu(child, builder)
        try:
            self._video_menu.tk_popup(event.x_root, event.y_root)
        finally:
            self._video_menu.grab_release()

    def _player_command(self, *args):
        if self._mpv is None:
            return
        try:
            return self._mpv.command(*args)
        except Exception as exc:
            messagebox.showerror('Odtwarzacz', str(exc), parent=self)

    def _toggle_mute(self):
        self._set_option('mute', not self._get_option('mute', False))

    def _delay_dialog(self, property_name, title):
        delay = simpledialog.askfloat(title, 'Sekundy (ujemne = wcześniej, dodatnie = później):',
                                      initialvalue=self._get_option(property_name, 0),
                                      minvalue=-60, maxvalue=60, parent=self)
        if delay is not None:
            self._set_option(property_name, delay)

    def _load_subtitles(self):
        path = filedialog.askopenfilename(parent=self, title='Wybierz napisy',
                                         filetypes=[('Napisy', '*.srt *.ass *.ssa *.vtt *.sub'), ('Wszystkie pliki', '*.*')])
        if path:
            self._player_command('sub-add', path, 'select')

    def _export_path(self, suffix):
        position = int(self._get_option('time_pos', 0) or 0)
        stem = sanitize_filename(Path(self._file.name).stem)[:100]
        name = f'{stem}-{position}s-{datetime.now():%Y%m%d-%H%M%S}-{suffix}.png'
        return filedialog.asksaveasfilename(parent=self, title='Zapisz obraz', defaultextension='.png',
                                          initialfile=name, filetypes=[('Obraz PNG', '*.png')])

    def _save_frame(self, mode):
        if self._mpv is None:
            return
        path = self._export_path('kadr')
        if not path:
            return
        try:
            self._mpv.command('screenshot-to-file', path, mode)
            self._option_status.set(f'Zapisano: {Path(path).name}')
        except Exception as exc:
            messagebox.showerror('Zrzut ekranu', str(exc), parent=self)

    def _save_thumbnail(self):
        if self._mpv is None:
            return
        try:
            from PIL import ImageOps
        except ImportError:
            messagebox.showinfo('Miniaturka', 'Miniaturki wymagają opcjonalnego Pillow.', parent=self)
            return
        path = self._export_path('miniaturka')
        if path:
            try:
                with self._mpv.screenshot_raw('video') as frame:
                    image = ImageOps.contain(frame, (480, 270))
                    image.save(path, format='PNG')
                    image.close()
                self._option_status.set(f'Zapisano: {Path(path).name}')
            except Exception as exc:
                messagebox.showerror('Miniaturka', str(exc), parent=self)

    def _contact_sheet_dialog(self):
        if self._mpv is None or self._export_busy:
            return
        duration = self._get_option('duration', 0) or 0
        if duration <= 0:
            messagebox.showinfo('Miniaturki', 'Poczekaj, aż odtwarzacz ustali długość filmu.', parent=self)
            return
        dialog = tk.Toplevel(self)
        dialog.title('Arkusz miniaturek')
        dialog.transient(self)
        body = ttk.Frame(dialog, padding=16)
        body.pack(fill='both', expand=True)
        ttk.Label(body, text='Klatki z całego filmu wraz ze znacznikami czasu.').pack(anchor='w')
        ttk.Label(body, text='Dla filmu online dekoder może pobrać cały film.\nGenerowanie działa w tle i można je anulować.').pack(anchor='w', pady=8)
        count = tk.StringVar(master=dialog, value='12')
        ttk.Combobox(body, textvariable=count, values=('8', '12', '16', '24'), state='readonly', width=8).pack(anchor='w')

        def start():
            path = self._export_path('arkusz')
            if path:
                selected_count = int(count.get())
                dialog.destroy()
                self._start_sheet_export(path, float(duration), selected_count)

        ttk.Button(body, text='Wybierz miejsce i generuj', command=start).pack(anchor='e', pady=(12, 0))
        dialog.grab_set()

    def _start_sheet_export(self, path, duration, count):
        self._export_busy = True
        self._export_cancel.clear()
        self._option_status.set('Generowanie miniaturek…')
        self._cancel_export_btn.pack(side='right', padx=3)
        source, title = self._stream_url, self._file.name

        def work():
            try:
                result = create_contact_sheet(source, path, duration, title, count, self._export_cancel.is_set)
                self._export_events.put(('done', result))
            except ExportCancelled:
                self._export_events.put(('cancelled', ''))
            except Exception as exc:
                self._export_events.put(('error', str(exc)))

        threading.Thread(target=work, name='video-thumbnails', daemon=True).start()

    def _poll_options(self):
        if self._options_closed:
            return
        while True:
            try:
                kind, value = self._export_events.get_nowait()
            except queue.Empty:
                break
            if kind == 'callback':
                value()
                continue
            self._export_busy = False
            self._cancel_export_btn.pack_forget()
            self._option_status.set('Anulowano' if kind == 'cancelled' else f'Zapisano: {Path(value).name}' if kind == 'done' else 'Błąd eksportu')
            if kind == 'error':
                messagebox.showerror('Arkusz miniaturek', value, parent=self)
        self._options_poll_id = self.after(100, self._poll_options)

    def _schedule_player(self, callback):
        # Callbacki mpv nie dotykają Tcl/Tk z obcego wątku.
        if not self._options_closed:
            self._export_events.put(('callback', callback))

    def _close_options(self):
        self._options_closed = True
        self._export_cancel.set()
        if self._options_poll_id:
            self.after_cancel(self._options_poll_id)
            self._options_poll_id = None
