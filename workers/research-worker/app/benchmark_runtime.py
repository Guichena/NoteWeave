"""Executable MA5 benchmark adapter for the existing Research Worker runtime."""

from __future__ import annotations

import os
import re
from contextlib import contextmanager

from app.benchmark import BenchmarkCase, BenchmarkExecution, BenchmarkProfile
from app.config import load_settings
from app.models import ResearchTaskInput
from app.runner import run_research_task


class ResearchTaskBenchmarkBoundary:
    _MODE_MAP = {
        "SEQUENTIAL": "SEQUENTIAL_V2",
        "PARALLEL": "LOCAL_PARALLEL",
    }

    def execute(self, profile: BenchmarkProfile, case: BenchmarkCase) -> BenchmarkExecution:
        if profile.execution_mode == "SPECULATIVE":
            raise RuntimeError("SPECULATIVE benchmark requires the distributed candidate-quorum harness")
        runtime_mode = self._MODE_MAP[profile.execution_mode]
        if case.task_input is None:
            raise ValueError("benchmark case requires task_input for Worker execution")
        if profile.provider_kind == "REAL":
            self._require_real_provider(profile)
        task_input = ResearchTaskInput.model_validate(case.task_input)
        if task_input.input_payload.question.strip() != case.question.strip():
            raise ValueError("benchmark case question differs from task_input question")

        updates = {
            "NOTEWEAVE_RESEARCH_AGENT_EXECUTION_MODE": runtime_mode,
            "NOTEWEAVE_RESEARCH_AGENT_MAX_CONCURRENCY": str(
                max(1, int(profile.budget.get("max_concurrency", 4)))
            ),
        }
        if profile.provider_kind == "SIMULATED":
            # A simulated run must never consume ambient real-provider credentials.
            updates.update({
                "NOTEWEAVE_RESEARCH_LLM_API_KEY": None,
                "NOTEWEAVE_RESEARCH_LLM_BASE_URL": None,
                "NOTEWEAVE_RESEARCH_LLM_MODEL": None,
            })
        with _temporary_environment(updates):
            _events, result = run_research_task(task_input)
        payload = dict(result.result_payload)
        llm = _dict(payload.get("llm_cost_ledger"))
        return BenchmarkExecution(
            result_payload=payload,
            usage={
                "search_calls": _search_calls(payload),
                "fetch_calls": len(_list(payload.get("fetched_documents"))),
                "read_calls": len(_list(payload.get("read_windows"))),
                "llm_calls": int(llm.get("provider_call_count") or 0),
                "input_tokens": int(llm.get("input_tokens") or 0),
                "output_tokens": int(llm.get("output_tokens") or 0),
                "estimated_cost": float(llm.get("estimated_cost") or 0.0),
                "retry_count": int(llm.get("retry_count") or 0),
            },
            http_429_count=int(llm.get("http_429_count") or 0),
            http_5xx_count=int(llm.get("http_5xx_count") or 0),
            termination_reason=_termination_reason(payload),
        )

    @staticmethod
    def _require_real_provider(profile: BenchmarkProfile) -> None:
        settings = load_settings()
        if not (
            settings.llm_api_key.strip()
            and settings.llm_base_url.strip()
            and settings.llm_model.strip()
        ):
            raise RuntimeError(
                "Research LLM configuration incomplete: API key, base URL, and model are required"
            )
        if settings.llm_model.strip() != profile.model.strip():
            raise RuntimeError("benchmark profile model differs from Research Worker model configuration")
        if bool(profile.source_policy.get("allow_external")):
            if not _has_external_search_credentials():
                raise RuntimeError(
                    "Research Search configuration incomplete: at least one configured provider API key is required"
                )
            if profile.source_policy.get("archive_required") is not True or not (
                settings.research_enable_url_reader
                or settings.research_jina_api_key.strip()
                or os.getenv("JINA_API_KEY", "").strip()
            ):
                raise RuntimeError(
                    "REAL external benchmark archive-ready snapshot transport is required and archive_required must be true"
                )


@contextmanager
def _temporary_environment(updates: dict[str, str | None]):
    previous = {key: os.environ.get(key) for key in updates}
    try:
        for key, value in updates.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        yield
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def _has_external_search_credentials() -> bool:
    providers = [
        item.strip()
        for item in (
            os.getenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER_CHAIN", "")
            or os.getenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER", "")
            or "serper"
        ).split(",")
        if item.strip()
    ]
    for index, provider in enumerate(providers):
        normalized = re.sub(r"[^A-Z0-9]+", "_", provider.upper()).strip("_")
        keys = [f"NOTEWEAVE_RESEARCH_{normalized}_API_KEY"]
        if index == 0:
            keys.extend(("NOTEWEAVE_RESEARCH_SEARCH_API_KEY", "SERPER_API_KEY", "SEARCH_API_KEY"))
        if any(os.getenv(key, "").strip() for key in keys):
            return True
    return False


def _search_calls(payload: dict[str, object]) -> int:
    summary = _dict(payload.get("toolbox_summary"))
    search = _dict(summary.get("search"))
    if "query_count" in search:
        return max(0, int(search.get("query_count") or 0))
    return len({
        str(item.get("query") or "")
        for item in _list(payload.get("search_hits"))
        if isinstance(item, dict) and str(item.get("query") or "").strip()
    })


def _termination_reason(payload: dict[str, object]) -> str:
    decision = _dict(payload.get("loop_decision"))
    return str(decision.get("reason_code") or decision.get("decision") or "COMPLETED")


def _dict(value: object) -> dict[str, object]:
    return value if isinstance(value, dict) else {}


def _list(value: object) -> list[object]:
    return value if isinstance(value, list) else []
