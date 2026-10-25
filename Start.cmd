@echo off
cd /d "%~dp0"
if not exist "%~dp0FTPPlayer\FTPPlayer.exe" (
  echo Brak gotowej aplikacji. Pobierz i wypakuj paczke Windows z GitHub Releases.
  pause
  exit /b 1
)
start "" "%~dp0FTPPlayer\FTPPlayer.exe"
