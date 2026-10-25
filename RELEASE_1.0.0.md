# FTP Player 1.0.0 — 25.10.2026

Nowa samodzielna aplikacja Windows oraz natywna aplikacja Android / Android TV.
Korzysta z wybranych komponentów playera i motywu Chomik Explorer, z własnym
transportem FTP/FTPS/HTTPS, konfiguracją, ikoną, ekranem startowym i repozytorium.

## Weryfikacja

- 18 testów Python: rzeczywiste listowanie HTTP i FTP, polskie nazwy i spacje,
  foldery, szyfrowanie i edycja kont, pełny plik, zakres, suffix, HEAD, 416,
  losowe adresy lokalne i odrzucanie traversal/komend w ścieżkach.
- Windows: rzeczywisty mpv przez most FTP — MP4/H.264/AAC, HEVC Main10/AC-3,
  AVI/MPEG-4/MP3, MPEG-2/MP2, WMV2/WMA2, Xvid/MP3, DivX/MP3, RealVideo 2/AC-3.
  Potwierdzone odtwarzanie i aktywne ścieżki wideo/audio.
- Mi 9: 6 testów instrumentalnych, w tym te same 8 próbek, ze sprawdzeniem
  liczników zdekodowanego obrazu i audio libVLC; FTP i HTTP Range; Android Keystore;
  otwarcie browsera, odczyt i przejście do kolejnego zdjęcia; fokus i D-pad w UI TV.
- Samodzielny EXE: Tk, ikona, GUI i libmpv uruchamiają się z paczki.
- Podpisany APK ARM64/ARMv7 został zbudowany i zweryfikowany przez narzędzia SDK.

Test interfejsu TV odbył się na Mi 9 z wariantem układu TV i zdarzeniami D-pad.
Nie zastępuje to testu na fizycznym Mi Box. Nie używano prywatnego serwera użytkownika.
Sprawdzenie FTPS dotyczy konfiguracji TLS i walidacji certyfikatów; rzeczywisty serwer
FTPS użytkownika nie został podany. Próbka RM zawiera RV20; osobnej próbki RV40/RMVB
nie było w zestawie. Obsługa poszczególnych profili zależy od dekodera i urządzenia.

## Najważniejsze elementy

- `ftp_player/core/models.py`, `accounts.py`, `remote.py`: modele, DPAPI, protokoły.
- `ftp_player/services/proxy.py`: tokeny lokalne, prawidłowe zakresy i połączenia
  zamykane po odpowiedzi, aby obsługiwać ponowne odczyty indeksów starszych AVI.
- `ftp_player/gui/app.py`: lista kont, filtry, przeglądarka, zdjęcia i pobieranie.
- `ftp_player/gui/player.py`, `player_options.py`: player mpv i opcje zaawansowane.
- `android/app/src/main/java/pl/ftpplayer/`: natywny UI, Keystore, backend, proxy,
  odtwarzacz libVLC, oszczędna przeglądarka zdjęć i lista z recyklingiem widoków.
- `assets/`, `android/.../res/`: wspólne ikony, banner TV i splash.
- `packaging/`: samodzielny ZIP, launcher i instalator Windows.
- `.github/workflows/ci.yml`: testy Windows/Linux i budowa Androida.

Paczki nie zawierają kont, haseł, materiałów testowych ani klucza podpisu Androida.
