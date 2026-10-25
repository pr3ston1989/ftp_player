"""Read physical Windows adapter traffic without dependencies or network requests."""

from __future__ import annotations

import ctypes as ct
import sys
import threading
import time
from collections.abc import Callable

from ..core.network_traffic import APP_TRAFFIC


class _Guid(ct.Structure):
    _fields_ = [("data1", ct.c_uint32), ("data2", ct.c_uint16),
                ("data3", ct.c_uint16), ("data4", ct.c_ubyte * 8)]


class _IfRow(ct.Structure):
    # Układ MIB_IF_ROW2 z Windows SDK, z naturalnym wyrównaniem 8 bajtów.
    _fields_ = [
        ("luid", ct.c_uint64), ("index", ct.c_uint32), ("guid", _Guid),
        ("alias", ct.c_uint16 * 257), ("description", ct.c_uint16 * 257),
        ("physical_address_length", ct.c_uint32),
        ("physical_address", ct.c_ubyte * 32), ("permanent_address", ct.c_ubyte * 32),
        *[(name, ct.c_uint32) for name in (
            "mtu", "type", "tunnel_type", "media_type", "physical_medium_type",
            "access_type", "direction_type")],
        ("flags", ct.c_ubyte),
        ("oper_status", ct.c_uint32), ("admin_status", ct.c_uint32),
        ("media_connect_state", ct.c_uint32), ("network_guid", _Guid),
        ("connection_type", ct.c_uint32),
        *[(name, ct.c_uint64) for name in (
            "tx_link_speed", "rx_link_speed", "in_octets", "in_ucast_pkts",
            "in_nucast_pkts", "in_discards", "in_errors", "in_unknown_protos",
            "in_ucast_octets", "in_multicast_octets", "in_broadcast_octets",
            "out_octets", "out_ucast_pkts", "out_nucast_pkts", "out_discards",
            "out_errors", "out_ucast_octets", "out_multicast_octets",
            "out_broadcast_octets", "out_qlen")],
    ]


class _IfTable(ct.Structure):
    _fields_ = [("count", ct.c_uint32), ("rows", _IfRow * 1)]


class WindowsNetworkCounters:
    """Return 64-bit receive/send counters keyed by physical adapter LUID."""

    def __init__(self) -> None:
        if sys.platform != "win32":
            raise OSError("Network counters are available on Windows only")
        self._api = ct.WinDLL("iphlpapi.dll")
        self._api.GetIfTable2.argtypes = [ct.POINTER(ct.POINTER(_IfTable))]
        self._api.GetIfTable2.restype = ct.c_uint32
        self._api.FreeMibTable.argtypes = [ct.c_void_p]
        self._api.FreeMibTable.restype = None

    def __call__(self) -> dict[int, tuple[int, int]]:
        table = ct.POINTER(_IfTable)()
        error = self._api.GetIfTable2(ct.byref(table))
        if error:
            raise OSError(error, "GetIfTable2 failed")
        try:
            count = table.contents.count
            rows = (_IfRow * count).from_address(
                ct.addressof(table.contents) + _IfTable.rows.offset)
            # Tylko aktywne interfejsy sprzętowe; bez podwójnego liczenia VPN,
            # wirtualnych kart i lokalnego połączenia odtwarzacza z proxy.
            return {row.luid: (row.in_octets, row.out_octets) for row in rows
                    if row.oper_status == 1 and row.flags & 1
                    and not row.flags & 2 and row.type not in (24, 131)}
        finally:
            self._api.FreeMibTable(table)


def calculate_rates(previous: dict[int, tuple[int, int]],
                    current: dict[int, tuple[int, int]], elapsed: float) -> tuple[float, float]:
    """Calculate bytes/s, ignoring new adapters and reset counters."""
    if elapsed <= 0:
        return 0.0, 0.0
    received = sent = 0
    for key in previous.keys() & current.keys():
        before, after = previous[key], current[key]
        received += max(0, after[0] - before[0])
        sent += max(0, after[1] - before[1])
    return received / elapsed, sent / elapsed


def format_network_rates(rates: tuple[float, float] | None, label: str = "Sieć PC",
                         estimated: bool = False) -> str:
    """Format decimal megabits per second for the desktop status bar."""
    if rates is None:
        return f"{label}: ↓ —  ↑ —"
    download, upload = (f"{max(0, value) * 8 / 1_000_000:.2f}".replace(".", ",")
                        for value in rates)
    return f"{label}: {'≈ ' if estimated else ''}↓ {download} Mb/s  ↑ {upload} Mb/s"


class NetworkUsageMonitor:
    """Sample counters off the UI thread and expose the most recent rates."""

    def __init__(self, reader: Callable[[], dict[int, tuple[int, int]]] | None = None) -> None:
        self._reader = reader
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._rates: tuple[float, float] | None = None
        self._app_rates: tuple[float, float] = (0.0, 0.0)
        self._thread = threading.Thread(target=self._run, name="network-usage", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    @property
    def rates(self) -> tuple[float, float] | None:
        with self._lock:
            return self._rates

    @property
    def app_rates(self) -> tuple[float, float]:
        with self._lock:
            return self._app_rates

    def _run(self) -> None:
        try:
            reader = self._reader or WindowsNetworkCounters()
        except (OSError, AttributeError):
            reader = None  # Licznik aplikacji działa też bez liczników Windows.
        previous = None
        previous_time = 0.0
        previous_app = APP_TRAFFIC.snapshot()
        app_time = time.monotonic()
        while not self._stop.is_set():
            try:
                current = reader() if reader else {}
                now = time.monotonic()
                rates = (calculate_rates(previous, current, now - previous_time)
                         if previous is not None else None)
                previous, previous_time = current, now
            except OSError:
                # Po błędzie nowa baza zamiast zaniżonej średniej lub starego odczytu.
                rates, previous = None, None
            app_now = time.monotonic()
            current_app = APP_TRAFFIC.snapshot()
            app_rates = calculate_rates(previous_app, current_app, app_now - app_time)
            app_rates = (app_rates[0] + APP_TRAFFIC.native_rate(), app_rates[1])
            previous_app, app_time = current_app, app_now
            with self._lock:
                self._rates = rates if reader else None
                self._app_rates = app_rates
            self._stop.wait(1.0)
