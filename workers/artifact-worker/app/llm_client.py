from __future__ import annotations

import json
import logging
import urllib.error
import urllib.request
from typing import Protocol

from app.config import load_settings
from app.io_limits import MAX_LLM_RESPONSE_BYTES


logger = logging.getLogger(__name__)


class RejectCredentialRedirects(urllib.request.HTTPRedirectHandler):
    """Never forward provider or internal-service credentials across a redirect."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        credential_headers = {
            "authorization",
            "x-noteweave-internal-token",
            "x-noteweave-callback-secret",
            "x-noteweave-outbox-delivery-token",
        }
        if any(name.lower() in credential_headers and value for name, value in req.header_items()):
            raise urllib.error.URLError("redirect rejected for credential-bearing request")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def credential_safe_urlopen(request: urllib.request.Request, *, timeout: float):
    opener = urllib.request.build_opener(RejectCredentialRedirects())
    return opener.open(request, timeout=timeout)


class LlmClient(Protocol):
    provider_name: str
    model_name: str

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        ...


class FakeLlmClient:
    provider_name = "fake"
    model_name = "fake-artifact-model"

    def __init__(self, responses_by_purpose: dict[str, str]) -> None:
        self.responses_by_purpose = responses_by_purpose
        self.calls: list[tuple[str, dict[str, object]]] = []

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        self.calls.append((purpose, payload))
        return self.responses_by_purpose.get(purpose, "")


class OpenAICompatibleLlmClient:
    provider_name = "openai-compatible"

    def __init__(
        self,
        api_key: str,
        model: str,
        base_url: str = "",
        timeout_seconds: int = 60,
    ) -> None:
        self.api_key = api_key
        self.model_name = model
        self.base_url = (base_url.strip() or "https://api.openai.com/v1").rstrip("/")
        self.timeout_seconds = timeout_seconds

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        contract = (
            "You proofread a speech-recognition transcript. Return only a JSON object "
            "{\"lines\": [...]} with exactly as many lines as supplied, in the same order. "
            "Fix only recognition errors: homophones, wrong technical terms, and punctuation. "
            "Keep the leading [mm:ss] timestamp of every line unchanged; never add, drop, "
            "merge, summarize, or reinterpret content."
            if purpose == "transcript_correction" else
            "Return only a JSON object shaped exactly like the supplied output_schema "
            "(video-knowledge-outline-v1: a title plus a concepts array). "
            "Use only the supplied corrected subtitles and frame OCR. "
            "Every claim quote must be copied verbatim from the cited segment's corrected_text."
            if purpose == "video_knowledge_plan" else
            "Follow the requested outline and output contract. "
            "Return only valid JSON with a sections array; every section must contain "
            "heading, body, and source_refs. source_refs may only use supplied source titles."
        )
        request_payload = {
            "model": self.model_name,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are the controlled generation node of NoteWeave Artifact Runtime. "
                        "Use only the supplied source materials for factual claims. "
                        + contract
                    ),
                },
                {
                    "role": "user",
                    "content": json.dumps(
                        {"purpose": purpose, "payload": payload},
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
            with credential_safe_urlopen(request, timeout=self.timeout_seconds) as response:
                response_body = response.read(MAX_LLM_RESPONSE_BYTES + 1)
                if len(response_body) > MAX_LLM_RESPONSE_BYTES:
                    raise ValueError(
                        f"Artifact LLM response exceeds the {MAX_LLM_RESPONSE_BYTES}-byte limit"
                    )
                data = json.loads(response_body.decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as exc:
            logger.warning("Artifact LLM completion failed for %s: %s", purpose, exc)
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
        timeout_seconds=max(1, settings.llm_timeout_seconds),
    )
