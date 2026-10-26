# FTP Player 1.1.1 — dziesięć rozłożonych klatek

- Film dzielony jest na 10 równych części; miniatura pochodzi ze środka każdej:
  5%, 15%, 25%, …, 95%. Dla bardzo krótkich materiałów Android zostawia sekundę
  zapasu przed końcem strumienia. Pokaz slajdów używa wszystkich 10 klatek.
- Windows pobiera klatki przez FFmpeg z ograniczonym rozpoznawaniem strumienia.
  Android najpierw używa systemowego MediaMetadataRetriever i najbliższych klatek
  kluczowych, z libVLC jako zapasem dla starszych kodeków. AVI w tym drugim trybie
  jest ponownie otwierane przy kolejnych punktach. Obrazy mają letterbox 16:9.
- Bufory prefetch libVLC dla podglądów są ograniczone. Lokalny most podglądów
  hamuje wyprzedzający odczyt (cel 4 MiB/s) i ogranicza odczyt do 128 MiB
  danych mediów na próbę.
  Te ograniczenia nie dotyczą normalnego odtwarzania ani pobierania plików.
- Podglądy HTTP wymagają prawidłowej odpowiedzi Range/206. Przy braku Range nie
  odczytują i nie pomijają początku pliku; odtwarzanie zachowuje dotychczasowy fallback.
  FTP/FTPS korzystają z REST. Limit nie obejmuje nagłówków, TLS i buforów systemowych.
- Nowa sygnatura cache oddziela zestawy 10 klatek od starych 3 klatek. Ponowne
  otwarcie używa gotowych JPEG bez pobierania danych filmu. Zmiana rozmiaru/mtime
  unieważnia podgląd. Istniejące limity dysku i TTL pozostają aktywne.
- Android zachowuje pozycję listy przy uzupełnianiu metadanych i sortowaniu w tle.

## Pomiar kontrolny

Syntetyczny MP4 H.264, 120 sekund, około 8 Mb/s, GOP 2 sekundy, rozmiar
119 820 304 bajtów. Pierwsze generowanie na Windows:

| Dostęp | Dane mediów odczytane przez aplikację | Klatek | JPEG łącznie |
|---|---:|---:|---:|
| HTTP Range | 6 160 384 B | 10 | 201 239 B |
| FTP REST | 6 553 600 B | 10 | 201 239 B |

Na Mi 9 systemowy ekstraktor odczytał dla tej samej próbki około 16,5 MB danych
mediów. To pomiar próby bez cache, nie pomiar całkowitych bajtów na interfejsie sieciowym.

Ponowne otwarcie cache: 0 B filmu. Są to odczytane dane mediów, a nie pomiar
pakietów na łączu; buforowanie po stronie serwera/systemu i narzut protokołów
mogą zwiększyć transfer. To wynik jednej próbki, nie prognoza dla każdego pliku.
Długie odstępy między klatkami kluczowymi, brak indeksu i wysoki bitrate zwiększają
koszt. Mały plik może zostać odczytany w większości lub w całości, także wielokrotnie.

## Zmienione pliki

- `ftp_player/services/thumbnails.py`: rozkład 10 punktów, cache, rozpoznawanie.
- `ftp_player/services/proxy.py`, `core/remote.py`: budżet i wymaganie Range dla podglądów.
- `ftp_player/gui/app.py`: 10 klatek w widokach, zwalnianie adresów podglądów.
- `android/.../VideoThumbnails.java`: dekoder, punkty, cache i bufory.
- `android/.../MediaServer.java`, `RemoteClient.java`: odczyty podglądów i limit danych.
- `android/.../MainActivity.java`: slajdy, etykiety i stabilna pozycja listy.
- `tests/test_library.py`, `test_transport.py`, `fixture_server.py`,
  `androidTest/.../PlayerSmokeTest.java`: regresje i pomiar na urządzeniu.
- `README.md`, `pyproject.toml`, `ftp_player/__init__.py`, `android/app/build.gradle`,
  `packaging/build_windows.py`, `packaging/README-WINDOWS.txt`: dokumentacja i wersja 1.1.1.

Mechanizm skoku: [FFmpeg -ss](https://www.ffmpeg.org/ffmpeg.html),
[FTP REST](https://www.rfc-editor.org/info/rfc3659/).
Bufory: [kod prefetch VLC 3](https://github.com/videolan/vlc/blob/3.0.x/modules/stream_filter/prefetch.c).

## Weryfikacja

- 26 testów Python; regresje normalnego FTP/HTTP i budżetu podglądów.
- 13 testów na Mi 9, obejmujących 10 zmieniających się klatek AVI, cache,
  brak Range, pobieranie, widoki i odtwarzanie dziewięciu próbek formatów.
- Dodatkowy test dłuższego filmu: 10 punktów 5–95%, różne klatki, cache bez
  ponownego zapisu JPEG i odczyt mniejszy od rozmiaru źródła.
- Gotowy runtime Windows: Tk, Pillow, libmpv i import aplikacji; integralność ZIP.
- Zwykłe odtwarzanie nadal korzysta z mpv/libVLC i nie ma limitu transferu podglądów.
