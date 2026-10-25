# Android / Android TV 1.0.1

Wariant TV ma teraz osobny boczny panel kont. Nawigacja, filtry i lista plików
korzystają z reszty szerokości ekranu. Wiersze plików są mniejsze, zachowując
duże etykiety i fokus pilota. Dzięki temu przy niskiej wysokości logicznej TV
lista nie jest wypierana przez ustawienia kont i nagłówek.

Układ i D-pad sprawdzone dodatkowym testem na Mi 9. Dekodery i transport pozostają
takie jak w wersji 1.0.0, która przeszła 6 testów urządzenia oraz 8 próbek formatów.
Testy instrumentalne zachowują i przywracają ewentualne istniejące konta.

Windows 1.0.0 pozostaje bez zmian:
[paczka ZIP Windows](https://github.com/pr3ston1989/ftp_player/releases/download/v1.0.0/FTPPlayer-1.0.0-Windows-x64.zip).

APK 1.0.1 jest wspólny dla telefonu Android i Android TV, ARM64/ARMv7.
Aktualizacja zachowuje podpis i dane kont. Nie wymaga zewnętrznego VLC.
