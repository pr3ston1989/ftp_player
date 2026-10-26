# FTP Player 1.1.2 — liczba klatek podglądu

- Wybór 1, 3, 5, 10 lub 20 klatek. Domyślnie 10; jedna klatka to statyczny
  podgląd ze środka filmu. Dla N klatek punkty wynoszą (i+0,5)/N czasu filmu.
- Android/TV: menu ⋯ → Liczba klatek podglądu. Windows: pole Klatki nad listą.
- Ustawienie jest zapamiętywane. Zmiana anuluje nieaktualne zadania i stosuje nową
  liczbę do aktualnie widocznych plików. Animacja działa również dla 3 i 20 klatek.
- Cache uwzględnia liczbę klatek; powrót do wcześniejszego wyboru korzysta z jego
  zapisanych podglądów. Dotychczasowy cache 10 klatek z 1.1.1 pozostaje zgodny.
- Więcej klatek może zwiększyć transfer, czas generowania i użycie pamięci.
  Limity odczytu podglądów i cache z 1.1.1 pozostają aktywne.
- Odtwarzanie i pobieranie plików zachowują dotychczasowe działanie.

## Zmienione pliki

- `ftp_player/services/thumbnails.py`: liczba klatek, punkty i klucze cache.
- `ftp_player/gui/app.py`: wybór, zapis ustawienia i animacja dla różnych długości.
- `android/.../VideoThumbnails.java`: bezpieczna zmiana liczby i anulowanie zadań.
- `android/.../MainActivity.java`: ustawienie i zapamiętywanie wyboru.
- `tests/test_library.py`, `androidTest/.../PlayerSmokeTest.java`: regresje ustawień.
- Pliki wersji, pakowania oraz README: aktualizacja do 1.1.2.

## Weryfikacja

- 27 testów Python, w tym odrębny cache i kompletność dla wszystkich pięciu wyborów.
- 14 testów na Mi 9: zapis i ponowne wczytanie ustawienia, generowanie 1 i 20 klatek,
  poprzednie regresje 10 klatek, odtwarzania, pobierania i braku HTTP Range.
- Okno Windows: wszystkie pięć wyborów i zapis do ustawień sprawdzone w aplikacji.
