"""Measure application HTTP traffic without changing global networking classes."""

from __future__ import annotations

import ipaddress
import threading
import time
from urllib.parse import urlsplit

import requests
from requests.adapters import HTTPAdapter
from urllib3.connection import HTTPConnection, HTTPSConnection
from urllib3.connectionpool import HTTPConnectionPool, HTTPSConnectionPool


def remote_url(url: str) -> bool:
    """Exclude files and loopback proxy traffic from network usage."""
    parsed = urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        return False
    return _remote_host(parsed.hostname)


def _remote_host(host: str) -> bool:
    host = host.lower().rstrip(".")
    if host == "localhost":
        return False
    try:
        address = ipaddress.ip_address(host)
        return not (address.is_loopback or
                    (getattr(address, "ipv4_mapped", None) and address.ipv4_mapped.is_loopback))
    except ValueError:
        return True


class TrafficCounter:
    """Keep thread-safe byte totals and short-lived native player rate estimates."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._received = self._sent = 0
        self._native: dict[int, tuple[float, float]] = {}

    def add(self, received: int = 0, sent: int = 0) -> None:
        with self._lock:
            self._received += received
            self._sent += sent

    def snapshot(self) -> dict[int, tuple[int, int]]:
        with self._lock:
            return {0: (self._received, self._sent)}

    def set_native_rate(self, source: object, rate: float) -> None:
        with self._lock:
            if rate > 0:
                self._native[id(source)] = (rate, time.monotonic())
            else:
                self._native.pop(id(source), None)

    def native_rate(self) -> float:
        now = time.monotonic()
        with self._lock:
            self._native = {key: value for key, value in self._native.items()
                            if now - value[1] < 2.0}
            return sum(value[0] for value in self._native.values())


APP_TRAFFIC = TrafficCounter()


class _CountingReader:
    """Count HTTP bytes below decompression, including chunked response framing."""

    def __init__(self, reader) -> None:
        self._reader = reader

    def __getattr__(self, name):
        return getattr(self._reader, name)

    def _read(self, method, *args, **kwargs):
        data = getattr(self._reader, method)(*args, **kwargs)
        if data:
            APP_TRAFFIC.add(received=len(data))
        return data

    def read(self, *args, **kwargs):
        return self._read("read", *args, **kwargs)

    def read1(self, *args, **kwargs):
        return self._read("read1", *args, **kwargs)

    def readline(self, *args, **kwargs):
        return self._read("readline", *args, **kwargs)

    def readinto(self, buffer):
        count = self._reader.readinto(buffer)
        if count:
            APP_TRAFFIC.add(received=count)
        return count


class _MeasuredConnection:
    def request(self, method, url, *args, **kwargs):
        self._traffic_remote = (remote_url(url) if url.startswith(("http://", "https://"))
                                else _remote_host(self._tunnel_host or self.host))
        return super().request(method, url, *args, **kwargs)

    def send(self, data):
        result = super().send(data)
        if getattr(self, "_traffic_remote", False) and isinstance(data, (bytes, bytearray, memoryview)):
            APP_TRAFFIC.add(sent=len(data))
        return result

    def getresponse(self):
        response = super().getresponse()
        # Pomiar na pliku HTTP, przed dekompresją; obejmuje read, iter_content
        # i stream, bez ponownego liczenia już buforowanych danych.
        original = getattr(response, "_fp", response)
        if (getattr(self, "_traffic_remote", False) and original is not None
                and getattr(original, "fp", None) is not None
                and not isinstance(original.fp, _CountingReader)):
            original.fp = _CountingReader(original.fp)
        return response


class _HTTPConnection(_MeasuredConnection, HTTPConnection):
    pass


class _HTTPSConnection(_MeasuredConnection, HTTPSConnection):
    pass


class _HTTPPool(HTTPConnectionPool):
    ConnectionCls = _HTTPConnection


class _HTTPSPool(HTTPSConnectionPool):
    ConnectionCls = _HTTPSConnection


class TrafficAdapter(HTTPAdapter):
    """Install measured connections only on sessions owned by this application."""

    @staticmethod
    def _configure(manager):
        # Kopia mapy: bez modyfikacji globalnych pul urllib3.
        manager.pool_classes_by_scheme = dict(manager.pool_classes_by_scheme)
        manager.pool_classes_by_scheme.update(http=_HTTPPool, https=_HTTPSPool)

    def init_poolmanager(self, *args, **kwargs):
        super().init_poolmanager(*args, **kwargs)
        self._configure(self.poolmanager)

    def proxy_manager_for(self, proxy, **kwargs):
        manager = super().proxy_manager_for(proxy, **kwargs)
        if not proxy.lower().startswith("socks"):
            self._configure(manager)
        return manager


def measured_session() -> requests.Session:
    """Create a normal requests session with isolated traffic instrumentation."""
    session = requests.Session()
    session.mount("http://", TrafficAdapter())
    session.mount("https://", TrafficAdapter())
    return session


def update_native_traffic(source: object, player, url: str) -> None:
    """Count direct mpv input only; local proxy input is already measured by HTTP."""
    rate = 0.0
    try:
        if remote_url(url) and not player.demuxer_cache_idle:
            rate = float(player.cache_speed or 0)
    except Exception:
        pass  # Statystyka jest opcjonalna; nie wpływa na odtwarzanie.
    APP_TRAFFIC.set_native_rate(source, rate)
