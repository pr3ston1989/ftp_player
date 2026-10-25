"""Minimal logging without server URLs or credential payloads."""
import logging


def get_logger(name):
    return logging.getLogger('ftp_player.' + name)
