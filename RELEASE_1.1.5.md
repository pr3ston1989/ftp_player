# FTP Player Windows 1.1.5 — kontrast list rozwijanych

Przyczyną nieczytelnych wartości było dziedziczenie jasnego tekstu z motywu
aplikacji przy zachowaniu jasnego tła readonly z domyślnego stylu Combobox.

- Jawne kolory tekstu, tła, strzałki i zaznaczenia w TCombobox.
- Poprawione stany readonly, focus, active i disabled.
- Rozwinięty popup jest klasycznym Listbox: otrzymuje osobne kolory i czcionkę.
- Poprawka obejmuje listy widoku, sortowania i liczby klatek. Działa z obydwiema
  paletami (ciemną i jasną), bez nowych zależności.

## Zmienione pliki

- `ftp_player/gui/theme.py`: styl i kolory rozwijanej części.
- `ftp_player/__init__.py`, `pyproject.toml`: wersja Windows 1.1.5.
- `packaging/build_windows.py`, `packaging/README-WINDOWS.txt`: gotowa paczka.
- `README.md`, `RELEASE_1.1.5.md`: opis aktualizacji.

## Sprawdzenie

Kontrola na prawdziwych kontrolkach Tk/ttk: kolor wyświetlanej wartości w stanach
readonly/focus/active/disabled i kolory popupu oraz zaznaczenia w obu paletach.
Sprawdzone pary tekst/tło osiągają kontrast co najmniej 4,5:1.
Kontrola składni/importów, kompilacja pełnej paczki oraz integralność ZIP.
Gotowa aplikacja została uruchomiona; kontrola wizualna została przerwana przez
użytkownika klawiszem Escape przed odczytaniem zrzutu okna.

[Android / Android TV 1.1.4 — APK](https://github.com/pr3ston1989/ftp_player/releases/download/v1.1.4/FTPPlayer-1.1.4-Android-TV.apk).
