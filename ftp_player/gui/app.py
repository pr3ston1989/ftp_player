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
import math
from datetime import datetime
from ..core.library import Library, sort_entries
from ..services.thumbnails import ThumbnailCache, FRAME_COUNTS

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
        self.library = Library()
        self.thumbnail_cache = ThumbnailCache(frame_count=self.config_obj.get('preview_frames', 10))
        self._thumb_workers = ThreadPoolExecutor(max_workers=1, thread_name_prefix='thumbnail')
        self._thumb_pending = set()
        self._thumb_images = {}
        self._thumb_paths = {}
        self._tile_nodes = {}
        self._hover_id = None
        self._slide_frame = 0
        self.store = AccountStore()
        self.accounts = []
        self.account = None
        self.path = '/'
        self.entries = []
        self._generation = 0
        self._closed = False
        self._playing = False
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
        self.after(1000, self._thumbnail_tick)
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
        ttk.Button(sidebar, text='★ Zakładki i ulubione', command=self.bookmarks).pack(fill='x', pady=(18, 5))
        ttk.Button(sidebar, text='☆ Zapisz ten folder', command=self.bookmark_folder).pack(fill='x', pady=5)
        ttk.Button(sidebar, text='⌂ Folder startowy', command=self.set_start_folder).pack(fill='x', pady=5)
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
        sorting = ttk.Frame(main); sorting.pack(fill='x', pady=(0, 8))
        self.view = tk.StringVar(value=self.config_obj.get('view', 'Szczegóły'))
        views = ttk.Combobox(sorting, state='readonly', textvariable=self.view, values=['Szczegóły','Małe kafelki','Średnie kafelki','Duże kafelki','Bardzo duże kafelki'], width=20)
        views.pack(side='left', padx=(0,12)); views.bind('<<ComboboxSelected>>', lambda e: self.change_view())
        ttk.Label(sorting, text='Sortuj: ', style='Muted.TLabel').pack(side='left')
        self.sort_key = tk.StringVar(value=self.config_obj.get('sort', 'Nazwa'))
        order = ttk.Combobox(sorting, state='readonly', textvariable=self.sort_key, values=['Nazwa', 'Rozmiar', 'Data', 'Format'], width=14)
        order.pack(side='left'); order.bind('<<ComboboxSelected>>', lambda e: self.render())
        self.sort_desc = tk.BooleanVar(value=self.config_obj.get('sort_desc', False))
        ttk.Checkbutton(sorting, text='Malejąco', variable=self.sort_desc, command=self.render).pack(side='left', padx=12)
        self.show_slides = tk.BooleanVar(value=True)
        ttk.Checkbutton(sorting, text='Pokaz slajdów', variable=self.show_slides).pack(side='right')
        self.preview_count = tk.StringVar(value=str(self.thumbnail_cache.frame_count))
        count = ttk.Combobox(sorting, state='readonly', textvariable=self.preview_count, values=FRAME_COUNTS, width=3)
        count.pack(side='right', padx=4); count.bind('<<ComboboxSelected>>', lambda e: self.change_preview_count())
        ttk.Label(sorting, text='Klatki:', style='Muted.TLabel').pack(side='right')
        browser = ttk.Frame(main); browser.pack(fill='both', expand=True)
        self.tree = ttk.Treeview(browser, columns=('kind', 'size', 'ext', 'date'), show='tree headings', selectmode='browse')
        self.tree.heading('#0', text='Nazwa pliku'); self.tree.heading('kind', text='Typ'); self.tree.heading('size', text='Rozmiar')
        for column,label in [('#0','Nazwa'),('size','Rozmiar'),('ext','Format'),('date','Data')]:
            self.tree.heading(column,command=lambda value=label:self.set_sort(value))
        self.tree.heading('ext', text='Format'); self.tree.heading('date', text='Data modyfikacji')
        self.tree.column('ext', width=70, stretch=False); self.tree.column('date', width=135, stretch=False)
        self.tree.column('#0', width=360, minwidth=180); self.tree.column('kind', width=90, stretch=False)
        self.tree.column('size', width=100, stretch=False, anchor='e')
        self.tree.pack(side='left', fill='both', expand=True)
        scroll = ttk.Scrollbar(browser, command=self.tree.yview); scroll.pack(side='right', fill='y')
        self._browser_scroll = scroll; self._browser_frame = browser
        scroll.configure(command=self.scroll_browser)
        self.tile_canvas = tk.Canvas(browser, bg='#0b1020', highlightthickness=0, yscrollcommand=scroll.set, takefocus=True)
        self.tile_canvas.bind('<Control-MouseWheel>', self.zoom_view)
        self.tree.bind('<Control-MouseWheel>', self.zoom_view)
        self.tile_canvas.bind('<Configure>', lambda e: self.draw_tiles())
        self.tile_canvas.bind('<MouseWheel>', lambda e: (self.tile_canvas.yview_scroll(-int(e.delta/120)*3, 'units'), self.draw_tiles()))
        self.tile_canvas.bind('<Button-1>', self.tile_click)
        self.tile_canvas.bind('<Double-1>', lambda e: self.open_selected())
        self.tile_canvas.bind('<Return>', lambda e: self.open_selected())
        for key, direction in [('Left',-1),('Right',1),('Up',-2),('Down',2)]:
            self.tile_canvas.bind('<'+key+'>', lambda e,d=direction: self.tile_key(d))
        self.tree.configure(yscrollcommand=scroll.set)
        ttk.Style(self).configure('Treeview', rowheight=62)
        self.tree.bind('<Motion>', lambda e: setattr(self, '_hover_id', self.tree.identify_row(e.y)))
        self.tree.bind('<Leave>', lambda e: setattr(self, '_hover_id', None))
        self.tree.bind('<Double-1>', lambda e: self.open_selected())
        self.tree.bind('<Return>', lambda e: self.open_selected())
        self.tree.bind('<<TreeviewSelect>>', self.selected)
        actions = ttk.Frame(main); actions.pack(fill='x', pady=12)
        ttk.Button(actions, text='▶ Otwórz / odtwórz', style='Accent.TButton', command=self.open_selected).pack(side='left')
        ttk.Button(actions, text='Pobierz plik', command=self.download).pack(side='left', padx=8)
        ttk.Button(actions, text='☆ Ulubiony plik', command=self.favorite_file).pack(side='left', padx=4)
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
            self.search.set(''); self.navigate(self.library.startup(self.account))

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
            except Exception:
                self.status.set('Nie udało się odświeżyć danych lub podglądu. Możesz kontynuować przeglądanie.')
        self.after(30, self._pump)

    def navigate(self, path, select_path=None, auto_open=False, notice=''):
        if not self.account:
            return
        self._generation += 1; generation = self._generation
        account = self.account
        self.path = path; self.path_var.set(account.name + '  /  ' + path.lstrip('/'))
        self.status.set('Wczytywanie katalogu…'); self.entries = []; self._thumb_images.clear(); self._thumb_paths.clear(); self.render()
        def done(rows, error):
            if generation != self._generation:
                return
            if error:
                unavailable = getattr(getattr(error, 'response', None), 'status_code', None) in (404, 410) or str(error).startswith('550')
                if path != '/' and unavailable:
                    self.navigate('/', notice='Zapisany katalog jest niedostępny. Otworzono katalog główny; zakładka pozostała do edycji.')
                    return
                self.status.set('Nie udało się otworzyć katalogu · sprawdź adres, dane konta i połączenie')
                messagebox.showerror('Połączenie z serwerem', 'Nie udało się wczytać katalogu. Sprawdź konto, protokół i uprawnienia.\n'
                    'HTTPS wymaga listy katalogów (index of) i ewentualnie Basic Auth.\n'
                    'FTPS używa jawnego TLS na porcie 21. Certyfikat musi być prawidłowy.', parent=self)
            else:
                self.entries = rows
                self.library.visited(account, path)
                self.render(); self.status.set(notice or f'{len(rows)} elementów · {account.scheme.upper()}')
                if select_path:
                    match = next((e for e in rows if e.path == select_path), None)
                    if match:
                        self.tree.selection_set(match.id); self.tree.see(match.id)
                        if auto_open:
                            self.open_selected()
                    else:
                        self.status.set('Ulubiony plik jest niedostępny. Jego folder został otwarty.')
                self._metadata(account, rows, generation)
        self.run_async(lambda: RemoteClient(account).list(path), done)

    def up(self):
        self.search.set(''); self.navigate(posixpath.dirname(self.path.rstrip('/')) or '/')

    def render(self):
        previous = self.tree.selection(); scroll_position = self.tree.yview()[0]
        self.tree.delete(*self.tree.get_children())
        needle = self.search.get().casefold()
        kind = self.filter_kind.get()
        icons = {'folder': '▸', 'video': '▶', 'audio': '♫', 'image': '▧', 'file': '·'}
        self._visible = {}
        key = {'Nazwa':'name','Rozmiar':'size','Data':'modified','Format':'ext'}[self.sort_key.get()]
        self.config_obj['sort'] = self.sort_key.get(); self.config_obj['sort_desc'] = self.sort_desc.get()
        for entry in sort_entries(self.entries, key, self.sort_desc.get()):
            if needle not in entry.name.casefold() or (kind != 'all' and not entry.directory and entry.kind != kind):
                continue
            self._visible[entry.id] = entry
            self.tree.insert('', 'end', iid=entry.id, text=f' {icons[entry.kind]}   {entry.name}',
                             values=self._values(entry), image=self._thumb_images.get(entry.id, [None])[0] or '')
        if previous and self.tree.exists(previous[0]):
            self.tree.selection_set(previous[0])
        self.tree.yview_moveto(scroll_position)
        if self.view.get() != "Szczegóły":
            self.tree.pack_forget(); self.tile_canvas.pack(side="left", fill="both", expand=True); self.draw_tiles()
        else:
            self.tile_canvas.pack_forget(); self.tree.pack(side="left", fill="both", expand=True, before=self._browser_scroll)

    def set_sort(self, label):
        self.sort_desc.set(not self.sort_desc.get() if self.sort_key.get() == label else False)
        self.sort_key.set(label)
        self.render()

    def zoom_view(self, event):
        views=['Szczegóły','Małe kafelki','Średnie kafelki','Duże kafelki','Bardzo duże kafelki']
        index=views.index(self.view.get())
        self.view.set(views[max(0,min(len(views)-1,index+(1 if event.delta>0 else -1)))])
        self.change_view()
        return 'break'

    def thumb_size(self):
        widths = {'Małe kafelki':128, 'Średnie kafelki':192, 'Duże kafelki':280, 'Bardzo duże kafelki':400}
        width = widths.get(self.view.get(), 96)
        return width, round(width*9/16)

    def change_preview_count(self):
        value = int(self.preview_count.get())
        self.config_obj['preview_frames'] = value
        self.config_obj.save()
        self.thumbnail_cache = ThumbnailCache(frame_count=value)
        self._thumb_images.clear(); self._thumb_paths.clear()
        if self.account:
            self.navigate(self.path)
        else:
            self.render()

    def change_view(self):
        self.config_obj['view'] = self.view.get()
        self._thumb_images.clear()
        self.render()

    def scroll_browser(self, *args):
        widget = self.tree if self.view.get() == 'Szczegóły' else self.tile_canvas
        widget.yview(*args)
        if widget is self.tile_canvas:
            self.draw_tiles()

    def draw_tiles(self):
        if self.view.get() == 'Szczegóły' or not hasattr(self, '_visible'):
            return
        canvas = self.tile_canvas; canvas.delete('all'); self._tile_nodes.clear()
        width,height = self.thumb_size(); cell_w,cell_h = width+24,height+84
        columns = max(1, canvas.winfo_width()//cell_w)
        entries = list(self._visible.values()); total = max(1, math.ceil(len(entries)/columns)*cell_h+8)
        canvas.configure(scrollregion=(0,0,max(1,canvas.winfo_width()),total), yscrollincrement=32)
        top = max(0,int(canvas.canvasy(0)//cell_h)); bottom = min(math.ceil(len(entries)/columns),top+math.ceil(max(1,canvas.winfo_height())/cell_h)+2)
        selected = self.tree.selection()
        for row in range(top,bottom):
            for col in range(columns):
                index=row*columns+col
                if index>=len(entries): break
                entry=entries[index]; x=col*cell_w+8; y=row*cell_h+8; tags=('entry:'+entry.id,)
                canvas.create_rectangle(x,y,x+cell_w-10,y+cell_h-10,fill='#162035',outline='#22d3ee' if selected and selected[0]==entry.id else '#26354d',width=2,tags=tags)
                if entry.id not in self._thumb_images and entry.id in self._thumb_paths:
                    try:self._thumb_images[entry.id]=[ImageTk.PhotoImage(Image.open(p).resize((width,height),Image.Resampling.LANCZOS)) for p in self._thumb_paths[entry.id]]
                    except OSError:self._thumb_paths.pop(entry.id,None)
                image=canvas.create_image(x+width/2+7,y+height/2+7,tags=tags)
                self._tile_nodes[entry.id]=image
                if entry.id in self._thumb_images:
                    canvas.itemconfigure(image,image=self._thumb_images[entry.id][self._slide_frame % len(self._thumb_images[entry.id]) if self.show_slides.get() else 0])
                else:
                    canvas.create_text(x+width/2+7,y+height/2+7,text='▸' if entry.directory else '▶' if entry.kind=='video' else '♫' if entry.kind=='audio' else '▧',fill='#22d3ee',font=('Segoe UI',32),tags=tags)
                text=entry.name
                max_chars=max(18,(width//7)*2-4)
                if len(text)>max_chars:text=text[:max_chars-1]+'…'
                canvas.create_text(x+8,y+height+12,text=text,width=width-6,anchor='nw',fill='#edf6ff',font=('Segoe UI',10),tags=tags)
                info='Folder' if entry.directory else entry.ext.upper()+' · '+(human_size(entry.size) if entry.metadata_known or entry.size else '—')
                canvas.create_text(x+8,y+cell_h-29,text=info,anchor='nw',fill='#94a3b8',font=('Segoe UI',9),tags=tags)
        if len(self._thumb_images)>45:
            for id in list(self._thumb_images):
                if id not in self._tile_nodes:
                    self._thumb_images.pop(id,None)

    def tile_click(self, event):
        self.tile_canvas.focus_set()
        tags=self.tile_canvas.gettags('current')
        id=next((t[6:] for t in tags if t.startswith('entry:')),None)
        if id and self.tree.exists(id):
            self.tree.selection_set(id);self.selected();self.draw_tiles()

    def tile_key(self, direction):
        entries=list(self._visible)
        if not entries:return
        current=self.tree.selection()
        index=entries.index(current[0]) if current and current[0] in entries else 0
        cell_w=self.thumb_size()[0]+24;cell_h=self.thumb_size()[1]+84;cols=max(1,self.tile_canvas.winfo_width()//cell_w)
        step=direction if abs(direction)==1 else cols*(1 if direction>0 else -1)
        index=max(0,min(len(entries)-1,index+step));self.tree.selection_set(entries[index]);self.selected()
        y=(index//cols)*cell_h;top=self.tile_canvas.canvasy(0);height=self.tile_canvas.winfo_height();total=max(1,math.ceil(len(entries)/cols)*cell_h+8)
        if y<top or y+cell_h>top+height:self.tile_canvas.yview_moveto(max(0,y-height+cell_h)/total)
        self.draw_tiles()

    @staticmethod
    def _values(entry):
        names = {'folder':'Folder', 'video':'Wideo', 'audio':'Audio', 'image':'Obraz', 'file':'Plik'}
        return names[entry.kind], (human_size(entry.size) if entry.metadata_known or entry.size else '—'), entry.ext.upper() if not entry.directory else '', datetime.fromtimestamp(entry.modified).strftime('%Y-%m-%d %H:%M') if entry.modified else '—'

    def _metadata(self, account, entries, generation):
        def work():
            for entry in entries:
                if self._closed or generation != self._generation:
                    return
                if entry.directory or entry.size_exact:
                    continue
                try:
                    RemoteClient(account).stat(entry)
                except Exception:
                    continue
                def update(e=entry):
                    if generation == self._generation:
                        if self.sort_key.get() in ('Rozmiar', 'Data'):
                            self.render()
                        elif self.tree.exists(e.id):
                            self.tree.item(e.id, values=self._values(e))
                self._queue.put(update)
        self._workers.submit(work)

    def _thumbnail_tick(self):
        if self._closed:
            return
        self._slide_frame = (self._slide_frame + 1) % 60
        if self.account:
            generation, account = self._generation, self.account
            visible = {self.tree.identify_row(y) for y in range(5, self.tree.winfo_height(), 32)} if self.view.get() == 'Szczegóły' else set(self._tile_nodes)
            for id in visible:
                entry = self._visible.get(id)
                if entry is None or entry.kind != 'video':
                    continue
                if id in self._thumb_images:
                    frame = self._slide_frame % len(self._thumb_images[id]) if self.show_slides.get() else 0
                    if self.view.get() == 'Szczegóły':
                        self.tree.item(id, image=self._thumb_images[id][frame])
                    elif id in self._tile_nodes:
                        self.tile_canvas.itemconfigure(self._tile_nodes[id], image=self._thumb_images[id][frame])
                        self.tile_canvas.tag_raise(self._tile_nodes[id])
                elif entry.size_exact and not self._playing and (generation, id) not in self._thumb_pending:
                    self._thumb_pending.add((generation, id))
                    def work(e=entry, a=account, g=generation):
                        url = self.proxy.register(a, e, preview=True)
                        try:
                            paths = self.thumbnail_cache.create(a, e, url, lambda: self._closed or self._playing or g != self._generation)
                        finally:
                            self.proxy.unregister(url)
                        def done():
                            self._thumb_pending.discard((g, e.id))
                            if paths and g == self._generation and not self._closed:
                                self._thumb_paths[e.id] = paths
                                self._thumb_images[e.id] = [ImageTk.PhotoImage(Image.open(p).resize(self.thumb_size(), Image.Resampling.LANCZOS)) for p in paths]
                                if self.view.get() == 'Szczegóły' and self.tree.exists(e.id):
                                    self.tree.item(e.id, image=self._thumb_images[e.id][0])
                                elif e.id in self._tile_nodes:
                                    self.tile_canvas.itemconfigure(self._tile_nodes[e.id], image=self._thumb_images[e.id][0])
                                    self.tile_canvas.tag_raise(self._tile_nodes[e.id])
                        self._queue.put(done)
                    self._thumb_workers.submit(work)
        self.after(1000, self._thumbnail_tick)

    def bookmark_folder(self):
        if self.account:
            self.library.toggle_folder(self.account, self.path)
            self.status.set('Zaktualizowano zakładki folderów')

    def set_start_folder(self):
        if self.account:
            use = messagebox.askyesnocancel('Folder startowy', 'Tak: ten folder jako startowy.\nNie: przywróć ostatnio otwarty folder.', parent=self)
            if use is not None:
                self.library.set_start(self.account, self.path if use else '')
                self.status.set('Zapisano ustawienie folderu startowego')

    def favorite_file(self):
        entry = self.selected()
        if entry and not entry.directory:
            self.library.toggle_file(self.account, entry)
            self.status.set('Zaktualizowano ulubione pliki')

    def bookmarks(self):
        if not self.account:
            return
        account = self.account
        window = tk.Toplevel(self); window.title('Zakładki i ulubione'); window.geometry('680x450')
        tree = ttk.Treeview(window, columns=('type',), show='tree headings'); tree.heading('#0', text='Nazwa / ścieżka'); tree.heading('type', text='Rodzaj'); tree.column('type', width=90, stretch=False); tree.pack(fill='both', expand=True, padx=12, pady=12)
        targets = {}
        for path in self.library.state(account)['folders']:
            id = tree.insert('', 'end', text=path, values=('Folder',)); targets[id] = ('folder', path)
        for entry in self.library.favorites(account):
            id = tree.insert('', 'end', text=entry.name + '   ' + entry.path, values=('Plik',)); targets[id] = ('file', entry)
        def open_item():
            if not tree.selection(): return
            kind, value = targets[tree.selection()[0]]
            window.destroy()
            if kind == 'folder': self.navigate(value)
            else: self.navigate(posixpath.dirname(value.path) or '/', value.path, True)
        def remove():
            if tree.selection():
                id = tree.selection()[0]; kind, value = targets[id]
                if kind == 'folder': self.library.toggle_folder(account, value)
                else: self.library.toggle_file(account, value)
                tree.delete(id)
        tree.bind('<Double-1>', lambda e: open_item())
        row = ttk.Frame(window, padding=12); row.pack(fill='x')
        ttk.Button(row, text='Otwórz i sprawdź dostępność', command=open_item).pack(side='left')
        ttk.Button(row, text='Usuń z zapisanych', command=remove).pack(side='right')

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
            self._playing = True
            player = open_player(self, entry, playlist, self.proxy.register(account, entry), self.theme,
                                 get_stream_url=lambda e: self.proxy.register(account, e), on_close=lambda: setattr(self, "_playing", False))
            if player is None:
                self._playing = False
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
        self._thumb_workers.shutdown(wait=False, cancel_futures=True)
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
