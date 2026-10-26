"""Build a complete Windows runtime; native dependencies are supplied explicitly."""
from pathlib import Path
import subprocess
import sys
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
command = [sys.executable, '-m', 'PyInstaller', '--noconfirm', '--windowed', '--onedir',
           '--name', 'FTPPlayer', '--icon', str(root/'assets/icon.ico'),
           '--paths', str(root), '--add-data', str(root/'assets')+';assets',
           '--hidden-import', 'mpv', '--collect-all', 'pillow_heif', '--collect-submodules', 'PIL']
for name in ('libmpv-2.dll', 'ffmpeg.exe', 'ffprobe.exe'):
    file = root/'native'/name
    if not file.is_file():
        raise SystemExit('Missing native dependency: ' + name)
    command += ['--add-binary', str(file)+';.']
for module in ('matplotlib','scipy','pandas','torch','numpy','PySide6','PyQt5'):
    command += ['--exclude-module', module]
subprocess.run(command+[str(root/'packaging/launcher.py')], cwd=root, check=True)
destination=root/'releases/FTPPlayer-1.1.3-Windows-x64'
destination.mkdir(parents=True, exist_ok=True)
shutil.copytree(root/'dist/FTPPlayer', destination/'FTPPlayer', dirs_exist_ok=True)
for name in ('Start.cmd','Install.cmd','Install.ps1','README-WINDOWS.txt','THIRD-PARTY.txt','FFMPEG-LICENSE.txt'):
    shutil.copy2(root/'packaging'/name, destination/name)
with zipfile.ZipFile(Path(str(destination)+'.zip'), 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for file in destination.rglob('*'):
        if file.is_file():
            archive.write(file, file.relative_to(destination))
print('Package:', Path(str(destination)+'.zip'))
