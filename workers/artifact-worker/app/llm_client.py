from __future__ import annotations

import json
import logging
import urllib.error
import urllib.request
from typing import Protocol

import httpx
from openai import OpenAI, OpenAIError

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


class _CredentialSafeTransport(httpx.BaseTransport):
    """SDK 的 HTTP 出口交给 credential_safe_urlopen：拒绝携带密钥的重定向，并限制响应体大小。"""

    def __init__(self, default_timeout_seconds: float) -> None:
        self.default_timeout_seconds = default_timeout_seconds

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        timeout = (request.extensions.get("timeout") or {}).get("read") or self.default_timeout_seconds
        headers = {
            key: value
            for key, value in request.headers.items()
            if key.lower() not in {"host", "connection", "accept-encoding", "content-length"}
        }
        url_request = urllib.request.Request(
            str(request.url), data=request.read() or None, headers=headers, method=request.method
        )
        try:
            with credential_safe_urlopen(url_request, timeout=timeout) as response:
                body = response.read(MAX_LLM_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as exc:
            try:
                error_body = exc.read(MAX_LLM_RESPONSE_BYTES) or b""
            except Exception:
                error_body = b""
            return httpx.Response(exc.code, content=error_body, request=request)
        except (urllib.error.URLError, OSError, ValueError) as exc:
            raise httpx.ConnectError(str(exc), request=request) from exc
        if len(body) > MAX_LLM_RESPONSE_BYTES:
            raise httpx.ReadError(
                f"Artifact LLM response exceeds the {MAX_LLM_RESPONSE_BYTES}-byte limit", request=request
            )
        return httpx.Response(200, headers={"content-type": "application/json"}, content=body, request=request)


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
        self.client = OpenAI(
            api_key=api_key,
            base_url=self.base_url,
            max_retries=0,
            timeout=timeout_seconds,
            http_client=httpx.Client(
                transport=_CredentialSafeTransport(timeout_seconds),
                follow_redirects=False,
            ),
        )

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
        try:
            completion = self.client.chat.completions.create(
                model=self.model_name,
                messages=[
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
                response_format={"type": "json_object"},
            )
        except (OpenAIError, ValueError) as exc:
            logger.warning("Artifact LLM completion failed for %s: %s", purpose, exc)
            return ""
        choices = getattr(completion, "choices", None)
        if not isinstance(choices, list) or not choices:
            return ""
        message = getattr(choices[0], "message", None)
        if message is None:
            return ""
        return str(getattr(message, "content", None) or "")


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
