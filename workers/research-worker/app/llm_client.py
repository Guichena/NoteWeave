from __future__ import annotations

import json
import hashlib
import logging
import time
import urllib.error
import urllib.request
from copy import copy
from typing import Callable, Protocol

import httpx
from openai import APIConnectionError, APIStatusError, APITimeoutError, OpenAI, OpenAIError

from app.config import load_settings
from app.http_security import credential_safe_urlopen
from app.json_repair import parse_json_payload


logger = logging.getLogger(__name__)


class LlmClient(Protocol):
    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        """Return a JSON-shaped model response for the given purpose."""


def _first_choice(data: object) -> dict[str, object]:
    choices = data.get("choices") if isinstance(data, dict) else None
    return choices[0] if isinstance(choices, list) and choices and isinstance(choices[0], dict) else {}


def _message_content(data: object) -> str:
    message = _first_choice(data).get("message")
    return str(message.get("content") or "") if isinstance(message, dict) else ""


def _finished_by_length(data: object) -> bool:
    """输出被长度上限截断：重试得到的仍是截断结果，交给调用方按截断处理。"""
    return _first_choice(data).get("finish_reason") == "length"


class FakeLlmClient:
    def __init__(self, responses_by_purpose: dict[str, str]) -> None:
        self.responses_by_purpose = responses_by_purpose
        self.calls: list[tuple[str, dict[str, object]]] = []

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        self.calls.append((purpose, payload))
        return self.responses_by_purpose.get(purpose, "")


class InvalidJsonFaultLlmClient:
    """Explicit local-eval fault: provider call succeeds but violates the JSON contract."""

    model = "fault-invalid-json"

    def __init__(self) -> None:
        self.call_count = 0

    def set_cancellation_checker(self, _checker: Callable[[], None] | None) -> None:
        return None

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        del purpose, payload
        self.call_count += 1
        return "{invalid-json"

    def usage_summary(self) -> dict[str, object]:
        return {"provider_call_count": self.call_count, "call_count": self.call_count, "calls": []}


class QuoteTamperFaultLlmClient:
    """Wrap a real provider and replace returned quotes with text absent from every source."""

    def __init__(self, delegate: LlmClient) -> None:
        self.delegate = delegate
        self.model = str(getattr(delegate, "model", "fault-quote-tamper"))

    def set_cancellation_checker(self, checker: Callable[[], None] | None) -> None:
        setter = getattr(self.delegate, "set_cancellation_checker", None)
        if callable(setter):
            setter(checker)

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        response = self.delegate.complete_json(purpose, payload)
        parsed = parse_json_payload(response)
        cards = parsed.get("evidence_cards") if isinstance(parsed, dict) else None
        if not isinstance(cards, list):
            return response
        for card in cards:
            if isinstance(card, dict):
                card["quote_text"] = "__NOTEWEAVE_TAMPERED_QUOTE_NOT_IN_SOURCE__"
                card["claim_text"] = "__NOTEWEAVE_TAMPERED_QUOTE_NOT_IN_SOURCE__"
        return json.dumps(parsed, ensure_ascii=False)

    def usage_summary(self) -> dict[str, object]:
        summary = getattr(self.delegate, "usage_summary", None)
        return summary() if callable(summary) else {"provider_call_count": 1, "call_count": 1, "calls": []}


class _CredentialSafeTransport(httpx.BaseTransport):
    """SDK 的 HTTP 出口交给 credential_safe_urlopen：只连公网地址、拒绝重定向，密钥不会被转发到其他主机。"""

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
                body = response.read()
        except urllib.error.HTTPError as exc:
            try:
                error_body = exc.read() or b""
            except Exception:
                error_body = b""
            return httpx.Response(exc.code, content=error_body, request=request)
        except (urllib.error.URLError, OSError, ValueError) as exc:
            raise httpx.ConnectError(str(exc), request=request) from exc
        return httpx.Response(200, headers={"content-type": "application/json"}, content=body, request=request)


class OpenAICompatibleLlmClient:
    """基于 OpenAI 官方 SDK 的 JSON 客户端，兼容任意 OpenAI 协议的服务。

    SDK 自带重试关闭，由本类逐次重试：每次尝试前检查取消，并记录每次的 HTTP 状态用于预算结算。
    """

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
        session_id: str = "",
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
        self.session_id = session_id
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

    def set_cancellation_checker(self, checker: Callable[[], None] | None) -> None:
        self.cancellation_checker = checker

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        purpose_config = self.purpose_options.get(purpose, {})
        model = str(purpose_config.get("model") or self.model)
        compacted_payload = _compact_context_payload(
            payload,
            max(1000, self.max_input_chars - self.context_safety_buffer_chars),
        )
        compaction = compacted_payload.get("_context_compaction")
        if isinstance(compaction, dict):
            # 通用压缩会截断列表和长文本，记录触发情况，用于判断是否需要按用途装配上下文
            logger.warning("LLM context compaction applied purpose=%s original_chars=%s budget=%s fallback=%s",
                           purpose, compaction.get("original_chars"), compaction.get("char_budget"),
                           compaction.get("fallback", "TRUNCATED"))
        request_payload: dict[str, object] = {
            "model": model,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are a NoteWeave research worker component. "
                        "Return only valid JSON for the requested contract: produce the result object described by "
                        "the payload's output_schema, schema or contract. Never repeat or quote the input payload."
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
        extra_body: dict[str, object] = {}
        thinking = purpose_config.get("thinking")
        if thinking not in ("enabled", "disabled"):
            # 规划、原文抽取和成文均有输出上限；思考模式可能先耗尽预算，正文仍为空。
            direct_json = purpose in {"research.plan.matrix", "research.extract", "research.synthesis"} and (
                model.lower().startswith("deepseek-v4") or model.lower() == "deepseek-flash")
            thinking = "disabled" if model.lower().startswith("glm-5") or direct_json else None
        if thinking:
            extra_body["thinking"] = {"type": thinking}
        if "temperature" in purpose_config:
            request_payload["temperature"] = float(purpose_config["temperature"])
        if "seed" in purpose_config:
            request_payload["seed"] = int(purpose_config["seed"])
        if "max_tokens" in purpose_config:
            request_payload["max_tokens"] = max(1, int(purpose_config["max_tokens"]))
        purpose_timeout_seconds = max(1, int(purpose_config.get("timeout_seconds") or self.timeout_seconds))
        # 规划由调用方保留一次修复机会，首个请求不应耗尽整个角色的两次预算。
        default_attempts = 1 if purpose == "research.plan.matrix" else self.max_attempts
        purpose_max_attempts = max(1, int(purpose_config.get("max_attempts") or default_attempts))
        started = time.perf_counter()
        if self.max_total_calls:
            purpose_max_attempts = min(purpose_max_attempts, max(0, self.max_total_calls - self._provider_call_count()))
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
        data: dict[str, object] | None = None
        termination_reason = "RETRY_EXHAUSTED"
        attempts = 0
        http_statuses: list[int] = []
        # 每次拿到响应的尝试都可能计费，逐次保留 usage；没有拿到响应的尝试用量未知
        attempt_usages: list[dict[str, object]] = []
        for attempt in range(1, purpose_max_attempts + 1):
            if self.cancellation_checker is not None:
                self.cancellation_checker()
            attempts = attempt
            try:
                completion = self.client.chat.completions.create(
                    **request_payload,
                    extra_body=extra_body or None,
                    extra_headers={"X-Session-ID": self.session_id} if self.session_id else None,
                    timeout=purpose_timeout_seconds,
                )
                data = completion.model_dump()
                usage = data.get("usage") if isinstance(data, dict) else None
                attempt_usages.append(usage if isinstance(usage, dict) else {})
                termination_reason = "SUCCESS"
                # 网关偶尔返回 200 但内容为空或不是 JSON（第九轮前 30 分钟约三分之一的抽取调用如此），
                # 这类结果重试通常就能拿到正常响应；最后一次仍不正常时照常返回，由调用方按非法 JSON 处理
                if attempt < purpose_max_attempts and not _finished_by_length(data) and not isinstance(
                    parse_json_payload(_message_content(data)), (dict, list)
                ):
                    logger.warning("LLM JSON completion attempt %s for %s returned no JSON content", attempt, purpose)
                    time.sleep(min(2 ** (attempt - 1), 4))
                    continue
                break
            except APIStatusError as exc:
                http_statuses.append(exc.status_code)
                logger.warning(
                    "LLM JSON completion attempt %s failed for %s: HTTP %s", attempt, purpose, exc.status_code
                )
                if 400 <= exc.status_code < 500 and exc.status_code not in {408, 429}:
                    termination_reason = f"NON_RETRYABLE_HTTP_{exc.status_code}"
                    break
                if attempt < purpose_max_attempts:
                    time.sleep(min(2 ** (attempt - 1), 4))
            except (OpenAIError, ValueError) as exc:
                termination_reason = ("PROVIDER_TIMEOUT" if isinstance(exc, APITimeoutError) else
                    "PROVIDER_CONNECTION_ERROR" if isinstance(exc, APIConnectionError) else
                    "INVALID_MODEL_OUTPUT" if isinstance(exc, ValueError) else "PROVIDER_ERROR")
                logger.warning("LLM JSON completion attempt %s failed for %s: %s", attempt, purpose, exc)
                if attempt < purpose_max_attempts:
                    time.sleep(min(2 ** (attempt - 1), 4))
        if extra_body:
            request_payload = {**request_payload, **extra_body}
        if data is None:
            self._record_call(
                purpose, request_payload, {}, attempts, started, termination_reason,
                http_statuses=http_statuses, attempt_usages=attempt_usages,
            )
            return ""

        choices = data.get("choices") if isinstance(data, dict) else None
        if not isinstance(choices, list) or not choices:
            self._record_call(
                purpose, request_payload, data, attempts, started, "EMPTY_CHOICES",
                http_statuses=http_statuses, attempt_usages=attempt_usages,
            )
            return ""
        message = choices[0].get("message") if isinstance(choices[0], dict) else None
        if not isinstance(message, dict):
            self._record_call(
                purpose, request_payload, data, attempts, started, "EMPTY_MESSAGE",
                http_statuses=http_statuses, attempt_usages=attempt_usages,
            )
            return ""
        self._record_call(
            purpose, request_payload, data, attempts, started, termination_reason,
            http_statuses=http_statuses, attempt_usages=attempt_usages,
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
        attempt_usages: list[dict[str, object]] | None = None,
    ) -> None:
        usage = response_payload.get("usage") if isinstance(response_payload, dict) else {}
        usage = usage if isinstance(usage, dict) else {}
        if attempt_usages is None:
            attempt_usages = [usage] if attempts > 0 and response_payload else []
        estimated_input = max(1, len(json.dumps(request_payload)) // 4)
        # 拿到响应的每次尝试都计入；没有 usage 的按请求长度估算输入
        input_tokens = sum(int(item.get("prompt_tokens") or estimated_input) for item in attempt_usages)
        output_tokens = sum(int(item.get("completion_tokens") or 0) for item in attempt_usages)
        if attempts > 0 and not attempt_usages:
            input_tokens = estimated_input
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
                "tokens_reported": (attempts > 0 and len(attempt_usages) == attempts and all(
                    "prompt_tokens" in item and "completion_tokens" in item for item in attempt_usages)),
                # 没拿到响应的尝试（超时、连接错误、服务端错误）是否计费未知，单独计数，不按零处理
                "unknown_usage_attempts": max(0, attempts - len(attempt_usages)),
                "price_known": input_rate > 0 or output_rate > 0,
                "latency_ms": round((time.perf_counter() - started) * 1000, 3),
                "estimated_cost": round(estimated_cost, 8),
                "termination_reason": termination_reason,
                "finish_reason": _first_choice(response_payload).get("finish_reason", ""),
                "reasoning_tokens": int((usage.get("completion_tokens_details") or {}).get("reasoning_tokens") or 0),
                "http_statuses": list(http_statuses or []),
            }
        )

    def _provider_call_count(self) -> int:
        return sum(int(item.get("attempt_count") or 0) for item in self.call_records)

    def usage_summary(self) -> dict[str, object]:
        return {
            "call_count": len(self.call_records),
            "reported_input_tokens": (sum(int(item["input_tokens"]) for item in self.call_records)
                if self.call_records and all(item.get("tokens_reported") for item in self.call_records) else None),
            "reported_output_tokens": (sum(int(item["output_tokens"]) for item in self.call_records)
                if self.call_records and all(item.get("tokens_reported") for item in self.call_records) else None),
            "provider_call_count": self._provider_call_count(),
            "blocked_call_count": sum(
                1 for item in self.call_records if item.get("termination_reason") == "CALL_BUDGET_EXHAUSTED"
            ),
            "input_tokens": sum(int(item["input_tokens"]) for item in self.call_records),
            "output_tokens": sum(int(item["output_tokens"]) for item in self.call_records),
            "estimated_cost": round(sum(float(item["estimated_cost"]) for item in self.call_records), 8),
            # 单价未配置时估算费用为 0 不代表免费
            "price_known": bool(self.call_records) and all(item.get("price_known", False) for item in self.call_records),
            "unknown_usage_attempts": sum(int(item.get("unknown_usage_attempts") or 0) for item in self.call_records),
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


def routing_session_id(kind: str, workspace_id: str, stable_id: str) -> str:
    if not workspace_id.strip() or not stable_id.strip():
        raise ValueError("routing session requires workspace and stable task identity")
    digest = hashlib.sha256(f"{kind}\0{workspace_id}\0{stable_id}".encode("utf-8")).hexdigest()
    return f"noteweave-{kind}-{digest}"


def bind_llm_session(
    client: LlmClient | None, kind: str, workspace_id: str, stable_id: str,
) -> LlmClient | None:
    """Bind a task-local client; shared executors must never mutate session state."""
    if isinstance(client, QuoteTamperFaultLlmClient):
        return QuoteTamperFaultLlmClient(bind_llm_session(client.delegate, kind, workspace_id, stable_id))
    if not isinstance(client, OpenAICompatibleLlmClient):
        return client
    bound = copy(client)
    bound.session_id = routing_session_id(kind, workspace_id, stable_id)
    bound.call_records = []
    return bound


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
