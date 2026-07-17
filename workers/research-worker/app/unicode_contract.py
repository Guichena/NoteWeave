"""Cross-runtime Unicode White_Space rules shared with the Java Backend."""

from __future__ import annotations


UNICODE_WHITE_SPACE_CODEPOINTS = frozenset({
    *range(0x0009, 0x000E),
    0x0020,
    0x0085,
    0x00A0,
    0x1680,
    *range(0x2000, 0x200B),
    0x2028,
    0x2029,
    0x202F,
    0x205F,
    0x3000,
})


def is_unicode_blank(value: str) -> bool:
    """Return true only for empty/all-White_Space using the shared fixed set."""
    return not value or all(ord(character) in UNICODE_WHITE_SPACE_CODEPOINTS for character in value)


def has_unicode_boundary_whitespace(value: str) -> bool:
    """Do not rely on Python ``strip`` or Java ``trim``/``isBlank`` semantics."""
    return bool(value) and (
        ord(value[0]) in UNICODE_WHITE_SPACE_CODEPOINTS
        or ord(value[-1]) in UNICODE_WHITE_SPACE_CODEPOINTS
    )
