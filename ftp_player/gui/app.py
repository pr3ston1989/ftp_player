"""Responsive desktop media library and server profile management."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import queue
import posixpath
import uuid
import json
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
import requests
from PIL import Image, ImageTk, ImageOps
import io

from ..core.accounts import AccountStore, data_dir
from ..core.models import Account
from ..core.remote import RemoteClient
from ..services.proxy import MediaProxy
from ..services.network_usage import NetworkUsageMonitor, format_network_rates
from .theme import Theme
from .player import open_player
from .formatting import human_size

ASSETS = Path(__file__).resolve().parents[2] / 'assets'


class Settings(dict):
    def __init__(self):
        self.path = data_dir() / 'settings.json'
        super().__init__()
        if self.path.exists():
            try:
                self.update(json.loads(self.path.read_text(encoding='utf-8')))
            except (ValueError, OSError):
                pass

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix('.tmp')
        temp.write_text(json.dumps(self), encoding='utf-8')
        temp.replace(self.path)


class AccountDialog(tk.Toplevel):
    def __init__(self, parent, account=None):
        super().__init__(parent)
        self.title('Ustawienia serwera' if account else 'Dodaj serwer')
        self.resizable(False, False)
        self.transient(parent)
        self.result = None
        self.account = account
        frame = ttk.Frame(self, padding=24)
        frame.pack(fill='both', expand=True)
        ttk.Label(frame, text='Twoja biblioteka. Twój serwer.', style='Title.TLabel').pack(anchor='w', pady=(0, 18))
        self.fields = {}
        for label, name, value in (
            ('Nazwa konta', 'name', account.name if account else ''),
            ('Adres serwera lub katalogu', 'address', account.address if account else 'https://'),
            ('Login (puste = dostęp anonimowy)', 'username', account.username if account else ''),
            ('Hasło', 'password', account.password if account else ''),
        ):
            ttk.Label(frame, text=label, style='Muted.TLabel').pack(anchor='w', pady=(9, 3))
            entry = ttk.Entry(frame, width=52, show='•' if name == 'password' else '')
            entry.insert(0, value)
            entry.pack(fill='x')
            self.fields[name] = entry
        ttk.Label(frame, text='HTTPS z listą katalogów • FTPS (szyfrowane FTP) • FTP\n'
                  'Przykład: https://serwer.pl/media/ lub ftps://serwer.pl/filmy/\n'
                  'HTTPS: logowanie Basic. FTP/HTTP przesyłają dane bez szyfrowania.',
                  style='Muted.TLabel', justify='left').pack(anchor='w', pady=14)
        row = ttk.Frame(frame); row.pack(fill='x', pady=(8, 0))
        ttk.Button(row, text='Anuluj', command=self.destroy).pack(side='right')
        ttk.Button(row, text='Zapisz konto', style='Accent.TButton', command=self.accept).pack(side='right', padx=8)
        self.fields['name'].focus_set()
        self.bind('<Return>', lambda e: self.accept())
        self.bind('<Escape>', lambda e: self.destroy())
        self.grab_set()

    def accept(self):
        try:
            values = {name: entry.get() for name, entry in self.fields.items()}
            values['name'] = values['name'].strip() or 'Mój serwer'
            self.result = Account(self.account.id if self.account else uuid.uuid4().hex, **values)
        except ValueError as exc:
            messagebox.showerror('Sprawdź dane', str(exc), parent=self)
            return
        self.destroy()


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title('FTP Player · Twoja prywatna biblioteka')
        self.geometry('1360x840'); self.minsize(960, 600)
        try:
            self.iconbitmap(str(ASSETS / 'icon.ico'))
        except tk.TclError:
            pass
        self.theme = Theme(self); self.theme.apply('dark')
        self.withdraw()
        splash = tk.Toplevel(self); splash.overrideredirect(True)
        splash_photo = ImageTk.PhotoImage(Image.open(ASSETS / 'splash.png').resize((660, 360)))
        splash._image = splash_photo
        ttk.Label(splash, image=splash_photo).pack()
        splash.geometry(f'+{max(0,(self.winfo_screenwidth()-660)//2)}+{max(0,(self.winfo_screenheight()-360)//2)}')
        self.update_idletasks()
        self.after(450, lambda: (splash.destroy(), self.deiconify()))
        self.config_obj = Settings()
        self.store = AccountStore()
        self.accounts = []
        self.account = None
        self.path = '/'
        self.entries = []
        self._generation = 0
        self._closed = False
        self._queue = queue.Queue()
        self._workers = ThreadPoolExecutor(max_workers=4, thread_name_prefix='ftp-player')
        self.proxy = MediaProxy()
        self._network_monitor = NetworkUsageMonitor(); self._network_monitor.start()
        self._build()
        self.protocol('WM_DELETE_WINDOW', self.close)
        self.bind('<Control-l>', lambda e: self.add_account())
        self.bind('<F5>', lambda e: self.navigate(self.path))
        self.bind('<Alt-Up>', lambda e: self.up())
        self.after(30, self._pump)
        self.after(1000, self._network_tick)
        self.after(100, self.load_accounts)

    def _build(self):
        header = ttk.Frame(self, padding=(22, 18)); header.pack(fill='x')
        logo = Image.open(ASSETS / 'icon.png').resize((44, 44))
        self._logo = ImageTk.PhotoImage(logo)
        ttk.Label(header, image=self._logo).pack(side='left', padx=(0, 12))
        brand = ttk.Frame(header); brand.pack(side='left')
        ttk.Label(brand, text='FTP PLAYER', style='Title.TLabel').pack(anchor='w')
        ttk.Label(brand, text='Prywatna biblioteka multimediów', style='Muted.TLabel').pack(anchor='w')
        ttk.Button(header, text='＋ Dodaj serwer', style='Accent.TButton', command=self.add_account).pack(side='right')
        content = ttk.Frame(self, padding=(16, 0)); content.pack(fill='both', expand=True)
        sidebar = ttk.Frame(content, width=230, padding=(10, 10)); sidebar.pack(side='left', fill='y', padx=(0, 16))
        sidebar.pack_propagate(False)
        ttk.Label(sidebar, text='TWOJE SERWERY', style='Muted.TLabel').pack(anchor='w', pady=(0, 12))
        self.account_list = tk.Listbox(sidebar, borderwidth=0, highlightthickness=0, exportselection=False, font=('Segoe UI', 11))
        self.theme.configure_tk_widget(self.account_list, 'surface')
        self.account_list.pack(fill='both', expand=True)
        self.account_list.bind('<<ListboxSelect>>', self.select_account)
        ttk.Button(sidebar, text='Ustawienia konta', command=self.edit_account).pack(fill='x', pady=5)
        ttk.Button(sidebar, text='Usuń konto', command=self.remove_account).pack(fill='x', pady=5)
        ttk.Label(sidebar, text='HTTPS • FTPS • FTP\nmpv · pełny odtwarzacz\nHasła chronione przez Windows',
                  style='Muted.TLabel').pack(anchor='w', pady=(18, 4))
        main = ttk.Frame(content); main.pack(side='left', fill='both', expand=True)
        nav = ttk.Frame(main); nav.pack(fill='x', pady=(6, 12))
        ttk.Button(nav, text='↑', width=3, command=self.up).pack(side='left', padx=(0, 6))
        self.path_var = tk.StringVar(value='Dodaj konto, aby rozpocząć')
        ttk.Label(nav, textvariable=self.path_var, style='Title.TLabel').pack(side='left', fill='x', expand=True)
        ttk.Button(nav, text='Odśwież', command=lambda: self.navigate(self.path)).pack(side='right')
        filters = ttk.Frame(main); filters.pack(fill='x', pady=(0, 12))
        self.filter_kind = tk.StringVar(value='all')
        for label, kind in [('Wszystko', 'all'), ('Wideo', 'video'), ('Muzyka', 'audio'), ('Zdjęcia', 'image')]:
            ttk.Radiobutton(filters, text=label, variable=self.filter_kind, value=kind, command=self.render).pack(side='left', padx=(0, 12))
        self.search = tk.StringVar()
        entry = ttk.Entry(filters, textvariable=self.search, width=27); entry.pack(side='right')
        ttk.Label(filters, text='Filtruj folder  ', style='Muted.TLabel').pack(side='right')
        self.search.trace_add('write', lambda *_: self.render())
        browser = ttk.Frame(main); browser.pack(fill='both', expand=True)
        self.tree = ttk.Treeview(browser, columns=('kind', 'size'), show='tree headings', selectmode='browse')
        self.tree.heading('#0', text='Nazwa pliku'); self.tree.heading('kind', text='Typ'); self.tree.heading('size', text='Rozmiar')
        self.tree.column('#0', width=460, minwidth=180); self.tree.column('kind', width=90, stretch=False)
        self.tree.column('size', width=100, stretch=False, anchor='e')
        self.tree.pack(side='left', fill='both', expand=True)
        scroll = ttk.Scrollbar(browser, command=self.tree.yview); scroll.pack(side='right', fill='y')
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.bind('<Double-1>', lambda e: self.open_selected())
        self.tree.bind('<Return>', lambda e: self.open_selected())
        self.tree.bind('<<TreeviewSelect>>', self.selected)
        actions = ttk.Frame(main); actions.pack(fill='x', pady=12)
        ttk.Button(actions, text='▶ Otwórz / odtwórz', style='Accent.TButton', command=self.open_selected).pack(side='left')
        ttk.Button(actions, text='Pobierz plik', command=self.download).pack(side='left', padx=8)
        self.detail = tk.StringVar(value='Wybierz plik. Enter lub podwójne kliknięcie otwiera multimedia.')
        ttk.Label(actions, textvariable=self.detail, style='Muted.TLabel').pack(side='right')
        footer = ttk.Frame(self, style='Toolbar.TFrame', padding=(22, 10)); footer.pack(fill='x')
        self.status = tk.StringVar(value='Gotowy · Dodaj swój pierwszy serwer')
        self.network = tk.StringVar()
        ttk.Label(footer, textvariable=self.network, style='Status.TLabel').pack(side='right')
        ttk.Label(footer, textvariable=self.status, style='Status.TLabel', width=1).pack(side='left', fill='x', expand=True)

    def load_accounts(self):
        try:
            self.accounts = self.store.load()
        except Exception:
            messagebox.showerror('Konta', 'Nie udało się odczytać zapisanych kont. Zachowano istniejący plik konfiguracji.', parent=self)
            return
        self.refresh_accounts()
        if self.accounts:
            index = next((i for i,a in enumerate(self.accounts) if a.id == self.config_obj.get('account')), 0)
            self.account_list.selection_set(index); self.select_account()

    def refresh_accounts(self):
        self.account_list.delete(0, 'end')
        for account in self.accounts:
            self.account_list.insert('end', '  ' + account.name + '  ·  ' + account.scheme.upper())

    def add_account(self):
        dialog = AccountDialog(self); self.wait_window(dialog)
        if dialog.result:
            proposed = self.accounts + [dialog.result]
            if self.save_accounts(proposed):
                self.refresh_accounts(); self.account_list.selection_set(len(self.accounts)-1); self.select_account()

    def save_accounts(self, proposed):
        try:
            self.store.save(proposed)
        except Exception:
            messagebox.showerror('Zapis konta', 'Nie udało się bezpiecznie zapisać konta. Dotychczasowe dane pozostają bez zmian.', parent=self)
            return False
        self.accounts = proposed
        return True

    def edit_account(self):
        if not self.account:
            return self.add_account()
        dialog = AccountDialog(self, self.account); self.wait_window(dialog)
        if dialog.result and self.save_accounts([dialog.result if a.id == self.account.id else a for a in self.accounts]):
            self.account = dialog.result; self.refresh_accounts(); self.navigate(self.path)

    def remove_account(self):
        if self.account and messagebox.askyesno('Usuń konto', 'Usunąć zapisane konto? Pliki na serwerze pozostaną bez zmian.', parent=self):
            if self.save_accounts([a for a in self.accounts if a.id != self.account.id]):
                self._generation += 1; self.account = None; self.entries = []; self.render(); self.refresh_accounts()
                self.path_var.set('Wybierz serwer'); self.status.set('Konto usunięte')

    def select_account(self, _event=None):
        selection = self.account_list.curselection()
        if selection:
            self.account = self.accounts[selection[0]]
            self.config_obj['account'] = self.account.id
            self.search.set(''); self.navigate('/')

    def run_async(self, work, done):
        def worker():
            try:
                value = work()
            except Exception as exc:
                self._queue.put(lambda e=exc: done(None, e))
            else:
                self._queue.put(lambda: done(value, None))
        self._workers.submit(worker)

    def _pump(self):
        if self._closed:
            return
        while True:
            try:
                callback = self._queue.get_nowait()
            except queue.Empty:
                break
            try:
                callback()
            except tk.TclError:
                pass
        self.after(30, self._pump)

    def navigate(self, path):
        if not self.account:
            return
        self._generation += 1; generation = self._generation
        account = self.account
        self.path = path; self.path_var.set(account.name + '  /  ' + path.lstrip('/'))
        self.status.set('Wczytywanie katalogu…'); self.entries = []; self.render()
        def done(rows, error):
            if generation != self._generation:
                return
            if error:
                self.status.set('Nie udało się otworzyć katalogu · sprawdź adres, dane konta i połączenie')
                messagebox.showerror('Połączenie z serwerem', 'Nie udało się wczytać katalogu. Sprawdź konto, protokół i uprawnienia.\n'
                    'HTTPS wymaga listy katalogów (index of) i ewentualnie Basic Auth.\n'
                    'FTPS używa jawnego TLS na porcie 21. Certyfikat musi być prawidłowy.', parent=self)
            else:
                self.entries = rows; self.render(); self.status.set(f'{len(rows)} elementów · {account.scheme.upper()}')
        self.run_async(lambda: RemoteClient(account).list(path), done)

    def up(self):
        self.search.set(''); self.navigate(posixpath.dirname(self.path.rstrip('/')) or '/')

    def render(self):
        self.tree.delete(*self.tree.get_children())
        needle = self.search.get().casefold()
        kind = self.filter_kind.get()
        labels = {'folder': 'Folder', 'video': 'Wideo', 'audio': 'Audio', 'image': 'Obraz', 'file': 'Plik'}
        icons = {'folder': '▸', 'video': '▶', 'audio': '♫', 'image': '▧', 'file': '·'}
        self._visible = {}
        for entry in self.entries:
            if needle not in entry.name.casefold() or (kind != 'all' and not entry.directory and entry.kind != kind):
                continue
            self._visible[entry.id] = entry
            self.tree.insert('', 'end', iid=entry.id, text=f' {icons[entry.kind]}   {entry.name}',
                             values=(labels[entry.kind], human_size(entry.size) if entry.size else '—'))

    def selected(self, _event=None):
        selection = self.tree.selection()
        item = self._visible.get(selection[0]) if selection else None
        self.detail.set(item.name[:55] if item else '')
        return item

    def open_selected(self):
        entry = self.selected()
        if not entry:
            return
        if entry.directory:
            self.search.set(''); self.navigate(entry.path); return
        if entry.kind == 'image':
            ImageViewer(self, entry, [e for e in self.entries if e.kind == 'image']); return
        if entry.kind in ('video', 'audio'):
            playlist = [e for e in self.entries if e.kind in ('video', 'audio')]
            account = self.account
            player = open_player(self, entry, playlist, self.proxy.register(account, entry), self.theme,
                                 get_stream_url=lambda e: self.proxy.register(account, e))
            if player is None:
                messagebox.showerror('Odtwarzacz', 'Nie znaleziono libmpv. Uruchom aplikację z kompletnej paczki Windows.', parent=self)
        else:
            self.download()

    def download(self):
        entry = self.selected()
        if not entry or entry.directory:
            return
        target = filedialog.asksaveasfilename(parent=self, initialfile=entry.name, title='Pobierz plik')
        if not target:
            return
        url = self.proxy.register(self.account, entry)
        self.status.set('Pobieranie: ' + entry.name)
        def work():
            temporary = Path(target).with_name(Path(target).name + '.' + uuid.uuid4().hex + '.ftp-part')
            try:
                with requests.get(url, stream=True, timeout=(15, 45)) as response:
                    response.raise_for_status()
                    with temporary.open('wb') as file:
                        for chunk in response.iter_content(256 * 1024):
                            if self._closed:
                                raise OSError('Closed')
                            file.write(chunk)
                temporary.replace(target)
            finally:
                temporary.unlink(missing_ok=True)
        self.run_async(work, lambda _, error: self.status.set('Pobieranie nie powiodło się' if error else 'Zapisano: ' + Path(target).name))

    def _network_tick(self):
        if self._closed:
            return
        self.network.set(format_network_rates(self._network_monitor.app_rates, 'FTP Player', True))
        self.after(1000, self._network_tick)

    def close(self):
        self._closed = True; self._network_monitor.stop()
        for child in self.winfo_children():
            if hasattr(child, '_close'):
                child._close()
        self.config_obj.save()
        self._workers.shutdown(wait=False, cancel_futures=True)
        threading_close = __import__('threading').Thread(target=self.proxy.close, daemon=True)
        threading_close.start()
        self.destroy()


class ImageViewer(tk.Toplevel):
    def __init__(self, app, entry, entries):
        super().__init__(app)
        self.app, self.entries = app, entries
        self.account = app.account
        self.index = entries.index(entry)
        self.generation = 0; self.zoom = 1.0; self.image = None
        self.title(entry.name); self.geometry('1100x780'); self.minsize(500, 350)
        bar = ttk.Frame(self, padding=10); bar.pack(fill='x')
        for text, action in [('← Poprzednie', lambda: self.move(-1)), ('Następne →', lambda: self.move(1)),
                             ('−', lambda: self.scale(.8)), ('＋', lambda: self.scale(1.25)), ('Dopasuj', self.fit)]:
            ttk.Button(bar, text=text, command=action).pack(side='left', padx=4)
        self.info = tk.StringVar(); ttk.Label(bar, textvariable=self.info, style='Muted.TLabel').pack(side='right')
        self.canvas = tk.Canvas(self, bg='#080d18', highlightthickness=0); self.canvas.pack(fill='both', expand=True)
        self.canvas.bind('<Configure>', lambda e: self.draw())
        self.bind('<Left>', lambda e: self.move(-1)); self.bind('<Right>', lambda e: self.move(1))
        self.bind('<Escape>', lambda e: self.destroy())
        self.bind('<MouseWheel>', lambda e: self.scale(1.2 if e.delta > 0 else .83))
        self.canvas.bind('<ButtonPress-1>', lambda e: self.canvas.scan_mark(e.x, e.y))
        self.canvas.bind('<B1-Motion>', lambda e: self.canvas.scan_dragto(e.x, e.y, gain=1))
        self.load()

    def move(self, delta):
        self.index = (self.index + delta) % len(self.entries); self.zoom = 1; self.load()

    def load(self):
        self.generation += 1; generation = self.generation
        entry = self.entries[self.index]; self.title(entry.name)
        self.info.set('Wczytywanie…'); self.image = None; self.canvas.delete('all')
        url = self.app.proxy.register(self.account, entry)
        def work():
            with requests.get(url, timeout=(15, 45), stream=True) as response:
                response.raise_for_status(); data = bytearray()
                for chunk in response.iter_content(64 * 1024):
                    data.extend(chunk)
                    if len(data) > 80 * 1024 * 1024:
                        raise ValueError('Image too large')
                source = Image.open(io.BytesIO(data)); source.load()
                return ImageOps.exif_transpose(source).convert('RGB')
        def done(image, error):
            if not self.winfo_exists() or generation != self.generation:
                return
            if error:
                self.info.set('Nie udało się wczytać obrazu'); return
            self.image = image
            self.info.set(f'{self.index+1}/{len(self.entries)}  ·  {image.width} × {image.height}')
            self.draw()
        self.app.run_async(work, done)

    def scale(self, factor):
        self.zoom = max(.1, min(12, self.zoom * factor)); self.draw()

    def fit(self):
        self.zoom = 1; self.draw()

    def draw(self):
        if self.image is None:
            return
        w, h = self.canvas.winfo_width(), self.canvas.winfo_height()
        ratio = min(w / self.image.width, h / self.image.height) * self.zoom
        ratio = min(ratio, 4096 / max(self.image.width, self.image.height))
        target = max(1, round(self.image.width * ratio)), max(1, round(self.image.height * ratio))
        self.photo = ImageTk.PhotoImage(self.image.resize(target, Image.Resampling.LANCZOS))
        self.canvas.delete('all'); self.canvas.create_image(w/2, h/2, image=self.photo)
        self.canvas.configure(scrollregion=self.canvas.bbox('all'))
