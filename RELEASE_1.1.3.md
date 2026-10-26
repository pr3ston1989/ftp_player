# FTP Player 1.1.3 — oszczędne podglądy i duże foldery

- Domyślnie jedna klatka ze środka filmu. Nowa opcja Wyłączone (Android/TV)
  lub Wył. (Windows) zatrzymuje generowanie i wczytywanie miniaturek, również
  tych już zapisanych. Istniejący cache pozostaje na dysku do ponownego użycia.
- Pozostaje wybór 3, 5, 10 i 20 klatek. Jedna klatka nie uruchamia timera
  pokazu slajdów na Androidzie. Zapisany wybór użytkownika jest zachowywany.
- Podglądy Androida są zamawiane dla rzeczywiście widocznych wierszy, po 180 ms
  bez przewijania. Przewijanie anuluje nieaktualne zadania i odczyty podglądów.

## Przyczyna przywieszania i poprawka

Na fizycznym Mi 9 test 3000 plików wykazał blokadę wątku interfejsu do 13 988 ms.
Naturalne sortowanie kompilowało wyrażenia regularne i tworzyło BigInteger dla
wielu porównań. Uzupełnienie metadanych każdego pliku ponownie filtrowało i
sortowało cały folder. Takie odświeżanie mogło zwielokrotnić koszt dużego katalogu.

- Filtrowanie i sortowanie ponad 256 wpisów odbywa się w tle. Wyniki nieaktualnych
  wyszukiwań i folderów są odrzucane. Klucze sortowania są niezmiennym snapshotem.
- Naturalne porównywanie liczb nie używa regexów ani BigInteger; nazwy i metadane
  są przygotowywane raz na wpis. Hasze zachowują format i zgodność starego cache.
- Aktualizacje metadanych są grupowane co 180 ms. Przy sortowaniu nazw/formatu
  odświeżane są widoczne wiersze, bez ponownego sortowania całego folderu.
- HEAD jest pobierany przede wszystkim dla widocznych plików. Po wybraniu
  sortowania rozmiaru/daty brakujące metadane całego folderu są uzupełniane w tle.

## Porównanie transferu

Ten sam syntetyczny MP4 H.264: 120 sekund, około 8 Mb/s, 119 820 304 B.
Puste cache dla każdej próby; dane mediów odczytane przez aplikację:

| Platforma | 1 klatka | 10 klatek | Oszczędność |
|---|---:|---:|---:|
| Windows, HTTP Range | 786 432 B | 6 160 384 B | 87,2% |
| Mi 9, HTTP Range | 1 859 584 B | 13 950 058 B | 86,7% |

Na Mi 9 inne powtórzenia dały 82–87% oszczędności. To pomiar jednej próbki;
nie gwarantuje identycznej proporcji dla każdego kontenera, kodeka i bitrate.
Odczyt nagłówków/indeksu ma stały koszt. Dane na interfejsie sieciowym mogą być
większe wskutek buforowania, nagłówków i TLS. Trafienie cache: 0 B filmu;
wyłączenie podglądów: 0 nowych odczytów filmu dla miniaturek. Lista katalogów
oraz zwykłe odtwarzanie/pobieranie nadal korzystają z sieci.

## Zmienione pliki

- `android/.../MainActivity.java`: praca listy w tle, grupowanie metadanych,
  widoczne elementy, tryb wyłączony i domyślna jedna klatka.
- `android/.../LibraryStore.java`: naturalne sortowanie i przygotowane klucze/hash.
- `android/.../VideoThumbnails.java`, `MediaServer.java`: anulowanie nieaktualnych
  podglądów, wyłączenie, domyślna liczba i zgodność dotychczasowych kluczy cache.
- `ftp_player/gui/app.py`, `services/thumbnails.py`: ustawienia Windows i brak
  uruchamiania dekodera w trybie Wył.
- `tests/test_library.py`, `androidTest/.../PlayerSmokeTest.java`: wyłączenie,
  ustawienia, 3000 wpisów, transfer oraz wcześniejsze regresje.
- `README.md`, pliki wersji i pakowania: wydanie 1.1.3.

## Weryfikacja

28 testów Python, pełny zestaw 16 testów na Mi 9 oraz dodatkowe sprawdzenie
nowego domyślnego ustawienia i ponownego uruchomienia z wyłączonymi podglądami.
Dodatkowa próba 3000 wpisów: wywołania na wątku interfejsu 1, 0 i 0 ms;
pełny wynik sortowania w tle został wczytany i sprawdzony.
Test dużego folderu sprawdza osobno koszt wywołania interfejsu i późniejsze
ukończenie sortowania w tle; nie mierzy szybkości indeksowania zdalnego serwera.
