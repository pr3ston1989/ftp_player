"""Create timestamped video contact sheets without touching active playback."""

from pathlib import Path
import math
import shutil
import subprocess
import tempfile
import time


class ExportCancelled(Exception):
    """Raised when the user cancels a video export."""


def timestamp(seconds: float) -> str:
    """Format a thumbnail timestamp as HH:MM:SS."""
    seconds = int(seconds)
    return f"{seconds // 3600:02d}:{seconds // 60 % 60:02d}:{seconds % 60:02d}"


def create_contact_sheet(source, destination, duration, title, count=12, cancel=None):
    """Decode one sequential stream and atomically save a labelled PNG sheet."""
    if count not in (8, 12, 16, 24) or not math.isfinite(duration) or duration <= 0:
        raise ValueError("Nieprawidłowy czas filmu lub liczba miniaturek")
    if cancel is not None and cancel():
        raise ExportCancelled()
    executable = shutil.which('ffmpeg')
    if not executable:
        raise RuntimeError("Brak ffmpeg w PATH. Zrzuty pojedynczych klatek nadal są dostępne.")
    try:
        from PIL import Image, ImageDraw, ImageFont, ImageOps
    except ImportError as exc:
        raise RuntimeError("Arkusz miniaturek wymaga opcjonalnego Pillow (grupa images w pyproject.toml).") from exc
    cancelled = cancel or (lambda: False)
    destination = Path(destination)
    if cancelled():
        raise ExportCancelled()
    with tempfile.TemporaryDirectory(prefix='ftp-player-thumbnails-') as temporary:
        directory = Path(temporary)
        interval = duration / count
        command = [executable, '-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
                   '-i', str(source), '-an', '-sn', '-vf',
                   f'fps=fps={1 / interval:.12g}:start_time=0,scale=320:180:force_original_aspect_ratio=decrease',
                   '-frames:v', str(count), str(directory / 'frame-%03d.png')]
        # Osobny dekoder nie przewija ani nie pauzuje głównego odtwarzacza.
        with tempfile.TemporaryFile() as diagnostics:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                       stderr=diagnostics,
                                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            began = time.monotonic()
            try:
                while process.poll() is None:
                    if cancelled():
                        raise ExportCancelled()
                    if time.monotonic() - began > 1800:
                        raise TimeoutError("Generowanie miniaturek przekroczyło 30 minut")
                    time.sleep(0.1)
                if process.returncode:
                    diagnostics.seek(0)
                    detail = diagnostics.read(4000).decode('utf-8', errors='replace')
                    raise RuntimeError(f"Nie udało się zdekodować filmu: {detail}")
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=3)
        frames = sorted(directory.glob('frame-*.png'))
        if not frames:
            raise RuntimeError("Film nie zawiera dostępnych klatek wideo")
        if cancelled():
            raise ExportCancelled()
        columns, width, height, gap = 4, 320, 180, 12
        rows = math.ceil(len(frames) / columns)
        sheet = Image.new('RGB', (columns * (width + gap) + gap, 72 + rows * (height + 30 + gap)), '#131316')
        draw = ImageDraw.Draw(sheet)
        try:
            font = ImageFont.truetype('segoeui.ttf', 16)
        except OSError:
            font = ImageFont.load_default()
        heading = title[:100]
        # Stary Pillow z fontem bitmapowym nie obsługuje wszystkich znaków Unicode.
        try:
            draw.text((gap, 12), heading, font=font, fill='#f4f4f5')
        except UnicodeEncodeError:
            draw.text((gap, 12), heading.encode('ascii', 'replace').decode(), font=font, fill='#f4f4f5')
        draw.text((gap, 39), f"{timestamp(duration)}  |  {len(frames)} klatek", font=font, fill='#a1a1aa')
        for index, frame in enumerate(frames):
            x, y = gap + index % columns * (width + gap), 72 + index // columns * (height + 30 + gap)
            with Image.open(frame) as image:
                fitted = ImageOps.contain(image.convert('RGB'), (width, height))
                sheet.paste(fitted, (x + (width - fitted.width) // 2, y + (height - fitted.height) // 2))
            draw.text((x, y + height + 4), timestamp(index * interval), font=font, fill='#f59e0b')
        # Atomowy zapis zapobiega pozostawieniu niekompletnego pliku docelowego.
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.chomik-', suffix='.png', delete=False) as output:
            staged = Path(output.name)
        try:
            sheet.save(staged, format='PNG')
            if cancelled():
                raise ExportCancelled()
            staged.replace(destination)
        finally:
            staged.unlink(missing_ok=True)
            sheet.close()
    return str(destination)
