from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Protocol

from app.config import load_settings


class LlmClient(Protocol):
    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        """Return a JSON-shaped model response for the given purpose."""


class FakeLlmClient:
    def __init__(self, responses_by_purpose: dict[str, str]) -> None:
        self.responses_by_purpose = responses_by_purpose
        self.calls: list[tuple[str, dict[str, object]]] = []

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        self.calls.append((purpose, payload))
        return self.responses_by_purpose.get(purpose, "")


class OpenAICompatibleLlmClient:
    """Minimal OpenAI-compatible JSON client without adding worker dependencies."""

    def __init__(
        self,
        api_key: str,
        model: str,
        base_url: str = "",
        timeout_seconds: int = 60,
    ) -> None:
        self.api_key = api_key
        self.model = model
        self.base_url = (base_url.strip() or "https://api.openai.com/v1").rstrip("/")
        self.timeout_seconds = timeout_seconds

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        request_payload = {
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are a NoteWeave research worker component. "
                        "Return only valid JSON for the requested contract."
                    ),
                },
                {
                    "role": "user",
                    "content": json.dumps(
                        {
                            "purpose": purpose,
                            "payload": payload,
                        },
                        ensure_ascii=False,
                    ),
                },
            ],
            "response_format": {"type": "json_object"},
        }
        request = urllib.request.Request(
            f"{self.base_url}/chat/completions",
            data=json.dumps(request_payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                data = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError):
            return ""

        choices = data.get("choices") if isinstance(data, dict) else None
        if not isinstance(choices, list) or not choices:
            return ""
        message = choices[0].get("message") if isinstance(choices[0], dict) else None
        if not isinstance(message, dict):
            return ""
        return str(message.get("content") or "")


def build_default_llm_client() -> LlmClient | None:
    settings = load_settings()
    api_key = settings.llm_api_key.strip()
    model = settings.llm_model.strip()
    if not api_key or not model:
        return None
    return OpenAICompatibleLlmClient(
        api_key=api_key,
        model=model,
        base_url=settings.llm_base_url,
    )
