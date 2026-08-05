from __future__ import annotations

import re


_SECRET_PATTERNS = (
    re.compile(
        r"(?i)((?:proxy-)?authorization\s*[:=]\s*(?:[A-Za-z][A-Za-z0-9+._-]*\s+)?)[^\s,;]+"
    ),
    re.compile(r"(?im)((?:set-cookie|cookie)\s*[:=]\s*)[^\r\n]+"),
    re.compile(r"(?i)(api[-_]?key\s*[:=]\s*)[^\s,;]+"),
    re.compile(r"(?i)((?:token|password|secret)\s*[:=]\s*)[^\s,;]+"),
)
_ABSOLUTE_PATH = re.compile(r"(?:(?:[A-Za-z]:[\\/])|/)(?:[^\s,;\"]+[\\/])*[^\s,;\"]*")


def sanitize_error_message(message: str) -> str:
    sanitized = str(message or "")
    for pattern in _SECRET_PATTERNS:
        sanitized = pattern.sub(r"\1[REDACTED]", sanitized)
    sanitized = _ABSOLUTE_PATH.sub("[PATH_REDACTED]", sanitized)
    return sanitized[:1000]


def sanitize_error_fields(value: object) -> object:
    """Recursively sanitize persisted fields whose contract is an error message."""
    if isinstance(value, dict):
        return {
            key: sanitize_error_message(str(item))
            if str(key) in {"error_message", "last_error_message"}
            else sanitize_error_fields(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [sanitize_error_fields(item) for item in value]
    return value
