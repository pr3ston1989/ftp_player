# FTP Player

Prywatna biblioteka multimediów na Windows, Android i Android TV. Aplikacja przegląda
serwer użytkownika i odtwarza wideo, muzykę oraz obrazy we własnym interfejsie.

## Gotowe aplikacje

Paczki są w [GitHub Releases](https://github.com/pr3ston1989/ftp_player/releases).

- **Windows 10/11 x64:** wypakuj cały ZIP; `Install.cmd` instaluje aplikację i tworzy
  skrót na pulpicie. `Start.cmd` uruchamia wersję przenośną. Python i VLC nie są potrzebne.
- **Android / Android TV, Android 6+:** zainstaluj APK ARM64/ARMv7. Ta sama paczka obsługuje
  dotyk i pilot. Ma osobny wpis w launcherze Android TV, banner i ekran startowy.

Po uruchomieniu wybierz **Dodaj serwer**. Wpisz nazwę, pełny adres, login i hasło.
Konta można później edytować, dodawać i usuwać. Usunięcie konta nie zmienia plików serwera.

## FTP czy HTTPS?

| Dostęp | Kiedy wybrać | Wymagania |
|---|---|---|
| HTTPS `https://host/media/` | Preferowany: szyfrowanie, prosta konfiguracja i sprawne przewijanie | lista katalogów Apache/nginx typu `index of`, opcjonalnie Basic Auth; najlepiej HTTP Range |
| FTPS `ftps://host/media/` | Gdy serwer udostępnia FTP z TLS | jawny TLS, zwykle port 21, pasywny transfer, prawidłowy certyfikat |
| FTP `ftp://host/media/` | Starsze serwery / zaufana sieć lokalna | tryb pasywny; dane nie są szyfrowane |

SFTP jest innym protokołem i nie jest obsługiwane w 1.0.0. HTTPS nie oznacza integracji
z dowolną stroną lub logowaniem formularzem: potrzebna jest dostępna lista katalogów.
W adresie możesz podać niestandardowy port i katalog początkowy.

## Multimedia i interfejs

- Wideo i audio: mpv/libmpv na Windows, pełny libVLC 3.6.5 na Androidzie. Bez wymogu
  instalowania zewnętrznego VLC. MP4, MKV/HEVC, AVI, MOV, MPG, WMV, RM/RMVB i inne
  kontenery obsługiwane przez te silniki; wielościeżkowe audio i napisy.
- Pauza, przewijanie, poprzedni/następny plik, proporcje obrazu, wybór ścieżek,
  tempo odtwarzania, zrzuty ekranu. Android: obrót i wybór dekodera/bufora.
- Windows: arkusze klatek i napisy z pliku, pobieranie, wskaźniki transferu.
- Zdjęcia: poprzednie/następne w folderze, powiększanie i przesuwanie; na Androidzie
  swipe/pinch i strzałki pilota. Windows zawiera Pillow + HEIF/AVIF.
- Filtry wideo/audio/zdjęć i nazw w całym wczytanym folderze. Małe wiersze folderów.
- Android używa listy z ponownym wykorzystaniem widoków i ogranicza rozdzielczość
  dekodowania zdjęć, aby oszczędzać pamięć telefonu i Mi Box.

Obsługa formatu nie gwarantuje płynności każdego profilu kodeka na każdym urządzeniu.
Wysoki bitrate wymaga odpowiedniej szybkości serwera/łącza. HEIF/AVIF/TIFF na Androidzie
zależą również od systemowego dekodera; nieobsługiwany obraz daje czytelny błąd.
HTTP bez Range może wymagać ponownego odczytania początku pliku podczas przewijania.

## Hasła i transport

Windows zapisuje hasła za pomocą DPAPI bieżącego użytkownika, Android używa AES-GCM
z kluczem Android Keystore. Metadane kont zostają na urządzeniu. Wersja Windows:
`%APPDATA%\FTPPlayer`. Nie ma synchronizacji haseł przez repozytorium.

Media są udostępniane playerowi przez losowy adres lokalny `127.0.0.1`; login i hasło
nie pojawiają się w tym adresie. HTTPS i FTPS sprawdzają certyfikaty. Odczyt katalogów
nie przechodzi na zewnętrzne domeny z linków autoindeksu. Narzędzie nie usuwa,
nie przenosi i nie zmienia nazw plików na serwerze.

## Architektura i budowanie

`ftp_player/core/`: konta, modele, FTP/FTPS/HTTP i pomiar ruchu.
`ftp_player/services/proxy.py`: lokalny most HTTP z poprawnymi `Range`, `HEAD`, `206`, `416`.
`ftp_player/gui/`: Tk/ttk, mpv i przeglądarka zdjęć.
`android/app/src/main/java/pl/ftpplayer/`: natywny Java UI, Keystore, transport i libVLC.

Projekt wykorzystuje wybrane komponenty playera i motywu Chomik Explorer. Nie wymaga
konta Chomikuj i nie modyfikuje instalacji Chomik Explorera.

Rozwój Windows: `pip install -e .`, następnie `python -m ftp_player`. Bibliotekę
`libmpv-2.dll`, `ffmpeg.exe`, `ffprobe.exe` umieść w `native/`. Źródła i licencje
komponentów są w `packaging/THIRD-PARTY.txt`. Budowa ZIP: `python packaging/build_windows.py`
(wymaga PyInstaller).

Android: JDK 17+, Android SDK 35, Gradle wrapper w `android/`. `gradlew assembleDebug`
buduje wersję testową. Wersja release używa lokalnego `signing/release.jks` albo
zmiennej `FTP_PLAYER_KEYSTORE` oraz `FTP_PLAYER_STORE_PASSWORD`/`FTP_PLAYER_KEY_PASSWORD`.
Klucz release pozostaje poza repozytorium — trzeba zachować jego prywatną kopię,
aby podpisywać przyszłe aktualizacje tym samym kluczem.

Testy: `pip install pytest pyftpdlib`, potem `pytest`. Syntetyczne testy urządzenia:
`tests/fixture_server.py` i `android/.../PlayerSmokeTest.java`; nie używają prywatnych kont.
