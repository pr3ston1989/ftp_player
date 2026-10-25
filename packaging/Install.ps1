$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'FTPPlayer'
$target = Join-Path $env:LOCALAPPDATA 'Programs\FTPPlayer'
if (-not (Test-Path -LiteralPath (Join-Path $source 'FTPPlayer.exe'))) { throw 'Rozpakuj caly ZIP przed instalacja.' }
New-Item -ItemType Directory -Path $target -Force | Out-Null
Copy-Item -Path (Join-Path $source '*') -Destination $target -Recurse -Force
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'FTP Player.lnk'))
$shortcut.TargetPath = Join-Path $target 'FTPPlayer.exe'
$shortcut.WorkingDirectory = $target
$shortcut.IconLocation = Join-Path $target '_internal\assets\icon.ico'
$shortcut.Save()
Write-Host 'Gotowe. FTP Player jest zainstalowany; skrot jest na pulpicie.'
Start-Process -FilePath (Join-Path $target 'FTPPlayer.exe')
