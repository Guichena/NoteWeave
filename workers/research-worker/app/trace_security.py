from __future__ import annotations

import hashlib
import re


_INLINE_SECRET_PATTERNS = (
    re.compile(r"(?i)\b(bearer\s+)[a-z0-9._~+/=-]+"),
    re.compile(r"(?i)\b(api[_-]?key|token|password|secret)(\s*[:=]\s*)[^\s,;]+"),
)


def sanitize_error_message(message: object, max_chars: int = 1000) -> str:
    value = str(message or "")
    value = _INLINE_SECRET_PATTERNS[0].sub(lambda match: f"{match.group(1)}[REDACTED]", value)
    value = _INLINE_SECRET_PATTERNS[1].sub(
        lambda match: f"{match.group(1)}{match.group(2)}[REDACTED]",
        value,
    )
    return value[:max_chars]


def sanitize_trace_payload(payload: dict[str, object]) -> dict[str, object]:
    sanitized: dict[str, object] = {}
    for key, value in payload.items():
        normalized = key.lower()
        if any(marker in normalized for marker in ("secret", "token", "password", "api_key", "authorization")):
            sanitized[key] = "[REDACTED]"
            continue
        if normalized in {"raw_text", "window_text", "full_text", "content", "html"}:
            encoded = str(value).encode("utf-8")
            sanitized[f"{key}_sha256"] = hashlib.sha256(encoded).hexdigest()
            sanitized[f"{key}_size"] = len(encoded)
            continue
        if isinstance(value, dict):
            sanitized[key] = sanitize_trace_payload(value)
        elif isinstance(value, list):
            sanitized[key] = [
                sanitize_trace_payload(item)
                if isinstance(item, dict)
                else sanitize_error_message(item, max_chars=500)
                if isinstance(item, str)
                else item
                for item in value[:20]
            ]
        elif isinstance(value, str) and len(value) > 500:
            sanitized[f"{key}_sha256"] = hashlib.sha256(value.encode("utf-8")).hexdigest()
            sanitized[f"{key}_size"] = len(value.encode("utf-8"))
        elif isinstance(value, str):
            sanitized[key] = sanitize_error_message(value, max_chars=500)
        else:
            sanitized[key] = value
    return sanitized
