FTP PLAYER 1.0.0 — WINDOWS 10/11 x64

1. Wypakuj CALY ZIP.
2. Uruchom Install.cmd (instalacja dla biezacego uzytkownika, skrot na pulpit)
   lub Start.cmd (wersja przenosna).
3. W aplikacji wybierz Dodaj serwer i wpisz adres, login i haslo.

Python, VLC ani zewnetrzne kodeki nie sa wymagane.

HTTPS: adres katalogu z lista plikow (index of), ewentualnie Basic Auth.
FTPS: jawny TLS, standardowo port 21. Certyfikat serwera musi byc prawidlowy.
FTP: tryb pasywny. Dziala takze dostep anonimowy (puste pole loginu).
SFTP nie jest FTP/FTPS i nie jest obslugiwane w tej wersji.

Hasla chroni Windows DPAPI. Dane kont sa w %APPDATA%\FTPPlayer.
Hasla nie sa przenoszone razem z paczka na inny komputer.

Podwojne klikniecie / Enter: otworz. F5: odswiez. Alt+Gora: folder nadrzedny.
Player: F11 — pelny ekran; Spacja — pauza; strzalki — przewijanie.
Zdjecia: strzalki — poprzednie/nastepne, kolko — zoom, przeciaganie — przesuwanie.

Formaty zaleza od dekoderow mpv/libVLC i wydajnosci urzadzenia.
Serwer musi wysylac dane szybciej niz bitrate filmu; HTTPS Range / FTP REST
pozwalaja na sprawne przewijanie. Serwery HTTP bez Range moga przewijac wolniej.

Zrodla i aktualizacje: https://github.com/pr3ston1989/ftp_player
