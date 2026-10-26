# FTP Player Android / Android TV 1.1.4

## Powiadomienia pobierania

- Postęp zawiera procenty oraz pobrane bajty/rozmiar całkowity. Przy nieznanym
  rozmiarze pokazuje liczbę bajtów i informację o braku rozmiaru. Pasek korzysta
  ze standardowego szablonu powiadomienia; aktualizacja nie częściej niż co 500 ms.
- Animowana strzałka jest używana tylko podczas transferu. Zakończenie ma ikonę
  statyczną, usuwa postęp i akcję Anuluj; powiadomienie znika po dotknięciu.
- Dotknięcie zakończonego pobrania uruchamia ACTION_VIEW z typem MIME pliku.
  Android wybiera domyślną aplikację lub systemowy wybór programu.
- SAF content URI zachowuje tymczasowe prawo odczytu. Pliki z Downloads aplikacji
  są udostępniane przez FileProvider, ograniczony do katalogów pobierania.
  Nie są udostępniane prywatne pliki ustawień i kont.
- Brak programu lub niedostępny plik daje komunikat zamiast błędu aplikacji.
- Historia pobierania ma przycisk Otwórz dla zapisanych plików.
- Zakończone powiadomienia starego formatu są aktualizowane na podstawie historii
  przy otwieraniu aplikacji; aktywny transfer nie jest przez tę naprawę zmieniany.

## Kolejka i błędy

Każdy plik ma osobny identyfikator powiadomienia i własne anulowanie. Anulowanie
nie przechodzi na następny plik w kolejce. Przerwany/niepełny transfer nie jest
opisywany jako Zapisano plik i nie oferuje otwarcia niekompletnego pliku.

## Zmienione pliki

- `android/app/src/main/java/pl/ftpplayer/DownloadService.java`: stany, postęp,
  identyfikatory, kolejka i naprawa starszych powiadomień.
- `android/app/src/main/java/pl/ftpplayer/OpenDownloadActivity.java`: domyślna
  aplikacja, MIME, URI i obsługa braku programu.
- `android/app/src/main/java/pl/ftpplayer/DownloadedFileProvider.java`,
  `android/app/src/main/res/xml/download_paths.xml`, `AndroidManifest.xml`: odczyt
  wyłącznie udostępnionego pliku, bez szerokich uprawnień do pamięci.
- `android/app/src/main/java/pl/ftpplayer/MainActivity.java`: otwieranie z historii
  i uruchomienie naprawy dawnych powiadomień w tle.
- `android/app/src/androidTest/java/pl/ftpplayer/PlayerSmokeTest.java`,
  `tests/fixture_server.py`: postęp, zakończenie, kliknięcie, anulowanie kolejki,
  urwany transfer i naprawa starego powiadomienia.
- `android/app/build.gradle`, `README.md`: numer i opis aktualizacji.

## Weryfikacja

Sześć testów na fizycznym Mi 9: realne powiadomienie podczas spowolnionego
transferu, zakończenie bez animacji/paska, wysłanie ACTION_VIEW po dotknięciu,
content URI z prawem odczytu, anulowanie z kolejnym pobraniem, przerwany transfer,
uwierzytelniony FTP i naprawa starego powiadomienia. Dodatkowo 28 testów Python
i kompilacja podpisanej paczki ARM64/ARMv7. Nie dodano nowych zależności:
FileProvider pochodzi z już używanego AndroidX.

[Udostępnianie pliku w Androidzie](https://developer.android.com/reference/androidx/core/content/FileProvider).

[Windows 1.1.3 — ZIP z instalatorem](https://github.com/pr3ston1989/ftp_player/releases/download/v1.1.3/FTPPlayer-1.1.3-Windows-x64.zip).
