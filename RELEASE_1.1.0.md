# FTP Player 1.1.0

## Przeglądanie i podglądy

- Trzy klatki z okolic 32%, 50% i 70% filmu, z niewielkim deterministycznym
  przesunięciem, zapisane jako osobne obrazy 16:9. Pokaz slajdów można wyłączyć.
- Cache rozróżnia serwer, konto, ścieżkę, rozmiar i datę modyfikacji. Zmieniony
  plik otrzymuje nowy podgląd. Gdy serwer nie podaje daty, obowiązuje ograniczony TTL.
- Windows: FFmpeg/ffprobe, limit cache 256 MB. Android: pojedynczy cichy dekoder
  libVLC, cache dyskowy 128 MB i pamięciowy 24 MB. Pobierane są podglądy tylko
  elementów widocznych na liście. Otwarcie playera zatrzymuje generowanie w tle.
- Windows: szczegóły i cztery rozmiary kafelków; zmiana rozmiaru również Ctrl+kółko.
  Wirtualizowany widok kafelków nie tworzy tysięcy widgetów dla dużych katalogów.
- Android/TV: przycisk **▦** przełącza zwartą listę i duże karty na szerokość listy.
  Foldery pozostają małe. Wybór widoku jest zapamiętywany.
- Sortowanie po nazwie, rozmiarze, dacie i rozszerzeniu, rosnąco/malejąco.
  Foldery są pierwsze; nieznane wartości pozostają na końcu. Nazwy sortowane naturalnie.
- Rozmiar, format i data są widoczne na liście. Metadane pochodzą z MLSD/LIST,
  indeksu HTTP i HEAD. Zaokrąglony rozmiar autoindeksu nigdy nie jest używany jako
  Content-Length/Content-Range: transport sprawdza rzeczywisty rozmiar pliku.

## Foldery i ulubione

- Ostatni poprawnie otwarty folder zapisywany osobno dla każdego konta/serwera.
- Opcjonalny folder startowy ma pierwszeństwo przed ostatnim folderem.
- Zakładki folderów i ulubione pliki; zapis nie zawiera tymczasowych URL playera.
- Otwarcie zapisanej pozycji sprawdza serwer. Niedostępny folder powoduje powrót
  do katalogu głównego z informacją. Niedostępny plik nie jest automatycznie odtwarzany.
  Zakładki nie są samoczynnie usuwane; błąd połączenia nie kasuje zapisów.

## Android i pobieranie

- Telefon w poziomie: boczny panel i zwarta belka; lista pozostaje widoczna.
- Przytrzymanie pliku lub Menu na pilocie: pobieranie i ulubione.
- Zapis przez systemowy wybór miejsca. Na TV bez takiego panelu — folder Downloads
  aplikacji, z podaną ścieżką w historii. Usługa pobierania działa w tle i pokazuje
  postęp, anulowanie oraz wynik. Nie oznacza przerwanego transferu jako sukcesu.
- MOV/QuickTime pozostaje obsługiwanym kontenerem; próbka jest w testach playera.

## Główne pliki

- `ftp_player/core/models.py`, `remote.py`, `library.py` — metadane, sortowanie i zapisy.
- `ftp_player/services/thumbnails.py` — trzy klatki i ograniczony cache Windows.
- `ftp_player/gui/app.py` — widoki, kolumny, slajdy, ulubione i walidacja ścieżek.
- `android/.../MainActivity.java`, `RemoteClient.java`, `LibraryStore.java` — UI i transport.
- `android/.../VideoThumbnails.java` — asynchroniczny dekoder i cache klatek.
- `android/.../DownloadService.java`, `AndroidManifest.xml` — pobieranie w tle.
- `tests/test_library.py`, `test_transport.py`, `androidTest/.../PlayerSmokeTest.java` — regresje.
