from __future__ import annotations

import json
from pathlib import Path


MAX_PROVIDER_PAYLOAD_BYTES = 1_000_000
MAX_PROVIDER_TEXT_BYTES = 4_000_000
MAX_LLM_RESPONSE_BYTES = 1_000_000


class ContentSizeLimitError(ValueError):
    pass


def validate_json_payload_size(
    payload: dict[str, object],
    *,
    label: str,
    max_bytes: int = MAX_PROVIDER_PAYLOAD_BYTES,
) -> None:
    try:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{label} must be JSON serializable") from exc
    if len(encoded) > max_bytes:
        raise ContentSizeLimitError(
            f"{label} exceeds the {max_bytes}-byte limit"
        )


def read_text_file_limited(
    path_value: object,
    *,
    max_bytes: int = MAX_PROVIDER_TEXT_BYTES,
) -> str:
    path = Path(str(path_value).strip())
    with path.open("rb") as handle:
        content = handle.read(max_bytes + 1)
    if len(content) > max_bytes:
        raise ContentSizeLimitError(
            f"provider text file exceeds the {max_bytes}-byte limit: {path}"
        )
    return content.decode("utf-8", errors="ignore").strip()
