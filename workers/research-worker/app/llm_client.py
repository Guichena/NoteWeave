from __future__ import annotations

import json
import hashlib
import logging
import time
import urllib.error
import urllib.request
from typing import Callable, Protocol

from app.config import load_settings
from app.http_security import credential_safe_urlopen


logger = logging.getLogger(__name__)


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
        max_attempts: int = 3,
        max_total_calls: int = 64,
        input_cost_per_million: float = 0.0,
        output_cost_per_million: float = 0.0,
        purpose_options: dict[str, dict[str, object]] | None = None,
        max_input_chars: int = 120000,
        context_safety_buffer_chars: int = 8000,
    ) -> None:
        self.api_key = api_key
        self.model = model
        self.base_url = (base_url.strip() or "https://api.openai.com/v1").rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.max_attempts = max(1, max_attempts)
        self.max_total_calls = max(0, max_total_calls)
        self.input_cost_per_million = max(0.0, input_cost_per_million)
        self.output_cost_per_million = max(0.0, output_cost_per_million)
        self.purpose_options = purpose_options or {}
        self.max_input_chars = max(1000, max_input_chars)
        self.context_safety_buffer_chars = max(0, context_safety_buffer_chars)
        self.call_records: list[dict[str, object]] = []
        self.cancellation_checker: Callable[[], None] | None = None

    def set_cancellation_checker(self, checker: Callable[[], None] | None) -> None:
        self.cancellation_checker = checker

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        purpose_config = self.purpose_options.get(purpose, {})
        model = str(purpose_config.get("model") or self.model)
        compacted_payload = _compact_context_payload(
            payload,
            max(1000, self.max_input_chars - self.context_safety_buffer_chars),
        )
        request_payload = {
            "model": model,
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
                            "payload": compacted_payload,
                        },
                        ensure_ascii=False,
                    ),
                },
            ],
            "response_format": {"type": "json_object"},
        }
        if "temperature" in purpose_config:
            request_payload["temperature"] = float(purpose_config["temperature"])
        if "max_tokens" in purpose_config:
            request_payload["max_tokens"] = max(1, int(purpose_config["max_tokens"]))
        purpose_timeout_seconds = max(1, int(purpose_config.get("timeout_seconds") or self.timeout_seconds))
        purpose_max_attempts = max(1, int(purpose_config.get("max_attempts") or self.max_attempts))
        started = time.perf_counter()
        if self.max_total_calls and self._provider_call_count() >= self.max_total_calls:
            self._record_call(
                purpose,
                request_payload,
                {},
                0,
                started,
                "CALL_BUDGET_EXHAUSTED",
            )
            return ""
        request = urllib.request.Request(
            f"{self.base_url}/chat/completions",
            data=json.dumps(request_payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        data: dict[str, object] | None = None
        termination_reason = "RETRY_EXHAUSTED"
        attempts = 0
        http_statuses: list[int] = []
        for attempt in range(1, purpose_max_attempts + 1):
            if self.cancellation_checker is not None:
                self.cancellation_checker()
            attempts = attempt
            try:
                with credential_safe_urlopen(request, timeout=purpose_timeout_seconds) as response:
                    data = json.loads(response.read().decode("utf-8"))
                termination_reason = "SUCCESS"
                break
            except urllib.error.HTTPError as exc:
                http_statuses.append(exc.code)
                logger.warning("LLM JSON completion attempt %s failed for %s: %s", attempt, purpose, exc)
                if 400 <= exc.code < 500 and exc.code not in {408, 429}:
                    termination_reason = f"NON_RETRYABLE_HTTP_{exc.code}"
                    break
                if attempt < purpose_max_attempts:
                    time.sleep(min(2 ** (attempt - 1), 4))
            except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as exc:
                logger.warning("LLM JSON completion attempt %s failed for %s: %s", attempt, purpose, exc)
                if attempt < purpose_max_attempts:
                    time.sleep(min(2 ** (attempt - 1), 4))
        if data is None:
            self._record_call(
                purpose, request_payload, {}, attempts, started, termination_reason,
                http_statuses=http_statuses,
            )
            return ""

        choices = data.get("choices") if isinstance(data, dict) else None
        if not isinstance(choices, list) or not choices:
            self._record_call(
                purpose, request_payload, data, attempts, started, "EMPTY_CHOICES",
                http_statuses=http_statuses,
            )
            return ""
        message = choices[0].get("message") if isinstance(choices[0], dict) else None
        if not isinstance(message, dict):
            self._record_call(
                purpose, request_payload, data, attempts, started, "EMPTY_MESSAGE",
                http_statuses=http_statuses,
            )
            return ""
        self._record_call(
            purpose, request_payload, data, attempts, started, termination_reason,
            http_statuses=http_statuses,
        )
        return str(message.get("content") or "")

    def _record_call(
        self,
        purpose,
        request_payload,
        response_payload,
        attempts,
        started,
        termination_reason,
        *,
        http_statuses: list[int] | None = None,
    ) -> None:
        usage = response_payload.get("usage") if isinstance(response_payload, dict) else {}
        usage = usage if isinstance(usage, dict) else {}
        input_tokens = (
            int(usage.get("prompt_tokens") or max(1, len(json.dumps(request_payload)) // 4))
            if attempts > 0
            else 0
        )
        output_tokens = int(usage.get("completion_tokens") or 0) if attempts > 0 else 0
        purpose_config = self.purpose_options.get(str(purpose), {})
        input_rate = float(purpose_config.get("input_cost_per_million") or self.input_cost_per_million)
        output_rate = float(purpose_config.get("output_cost_per_million") or self.output_cost_per_million)
        estimated_cost = (
            input_tokens * input_rate
            + output_tokens * output_rate
        ) / 1_000_000
        self.call_records.append(
            {
                "purpose": purpose,
                "model": str(request_payload.get("model") or self.model),
                "attempt_count": attempts,
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "latency_ms": round((time.perf_counter() - started) * 1000, 3),
                "estimated_cost": round(estimated_cost, 8),
                "termination_reason": termination_reason,
                "http_statuses": list(http_statuses or []),
            }
        )

    def _provider_call_count(self) -> int:
        return sum(1 for item in self.call_records if int(item.get("attempt_count") or 0) > 0)

    def usage_summary(self) -> dict[str, object]:
        return {
            "call_count": len(self.call_records),
            "provider_call_count": self._provider_call_count(),
            "blocked_call_count": sum(
                1 for item in self.call_records if item.get("termination_reason") == "CALL_BUDGET_EXHAUSTED"
            ),
            "input_tokens": sum(int(item["input_tokens"]) for item in self.call_records),
            "output_tokens": sum(int(item["output_tokens"]) for item in self.call_records),
            "estimated_cost": round(sum(float(item["estimated_cost"]) for item in self.call_records), 8),
            "retry_count": sum(max(0, int(item["attempt_count"]) - 1) for item in self.call_records),
            "http_429_count": sum(
                int(status == 429)
                for item in self.call_records
                for status in item.get("http_statuses", [])
            ),
            "http_5xx_count": sum(
                int(500 <= status < 600)
                for item in self.call_records
                for status in item.get("http_statuses", [])
            ),
            "calls": list(self.call_records),
        }


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
        timeout_seconds=settings.llm_timeout_seconds,
        max_attempts=settings.llm_max_attempts,
        max_total_calls=settings.llm_max_total_calls,
        input_cost_per_million=settings.llm_input_cost_per_million,
        output_cost_per_million=settings.llm_output_cost_per_million,
        purpose_options=settings.llm_purpose_options,
        max_input_chars=settings.llm_max_input_chars,
        context_safety_buffer_chars=settings.llm_context_safety_buffer_chars,
    )


def _compact_context_payload(payload: dict[str, object], char_budget: int) -> dict[str, object]:
    serialized = json.dumps(payload, ensure_ascii=False, default=str)
    if len(serialized) <= char_budget:
        return payload
    compacted = _truncate_context_value(payload)
    compacted_serialized = json.dumps(compacted, ensure_ascii=False, default=str)
    metadata = {
        "applied": True,
        "original_chars": len(serialized),
        "original_sha256": hashlib.sha256(serialized.encode("utf-8")).hexdigest(),
        "char_budget": char_budget,
    }
    if len(compacted_serialized) <= char_budget:
        return {**compacted, "_context_compaction": metadata}
    preview_budget = max(0, char_budget - 1000)
    return {
        "_context_compaction": {**metadata, "fallback": "SERIALIZED_PREVIEW"},
        "payload_preview_json": compacted_serialized[:preview_budget],
    }


def _truncate_context_value(value: object) -> object:
    if isinstance(value, str):
        if len(value) <= 8000:
            return value
        return value[:6000] + f"\n[TRUNCATED {len(value) - 8000} CHARS]\n" + value[-2000:]
    if isinstance(value, list):
        kept = [_truncate_context_value(item) for item in value[:12]]
        if len(value) > 12:
            kept.append({"_truncated_items": len(value) - 12})
        return kept
    if isinstance(value, dict):
        return {str(key): _truncate_context_value(item) for key, item in value.items()}
    return value
