"""Research Worker 的显式 LLM 测试 fixture。

Fake client 不是 autouse；需要 LLM 集成路径的模块必须显式 opt-in。
因此 RULE-mode 测试保留生产行为，无 LLM 时不会伪造 evidence。
"""

from __future__ import annotations

import json
from typing import Any

import pytest


class _FakeLlmClient:
    """最小 Fake LLM,产出符合 research.extract / research.verify.cell schema 的 JSON。

    关键设计:
    - research.extract 返回 1 张 SUPPORTS 卡片(从第一个 window 抽取,ID 兼容旧测试的 ev-N 格式)
    - research.verify.cell 返回 SUPPORTS verdict(高 confidence)
    - 不可调用时返回空字符串(触发 CellVerifier rule-mode fallback)
    """

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def complete_json(self, purpose: str, payload: dict[str, Any]) -> str:
        self.calls.append((purpose, payload))
        if purpose == "research.extract":
            windows = payload.get("windows") or []
            window_id = windows[0].get("window_id", "window-1") if windows else "window-1"
            first_window_text = str(windows[0].get("text") or "") if windows else ""
            grounded_quote = first_window_text[:160].strip() or "No opened window text."
            schema_columns = [
                str(item).strip()
                for item in payload.get("schema_columns", [])
                if str(item).strip()
            ] or ["claim"]
            window_text = " ".join(str(window.get("text") or "") for window in windows).lower()
            has_conflict = any(
                token in window_text
                for token in ["conflict", "contradict", "opposing", "uncertainty", "冲突", "反证"]
            )
            if has_conflict:
                return json.dumps(
                    {
                        "evidence_cards": [
                            {
                                "window_id": window_id,
                                "entity_id": "",
                                "column_key": schema_columns[0],
                                "claim_text": "The source conflicts with the current research path.",
                                "quote_text": grounded_quote,
                                "relation_type": "CONFLICTS",
                                "support_score": 0.35,
                                "conflict_score": 0.82,
                            }
                        ]
                    }
                )
            return json.dumps(
                {
                    "evidence_cards": [
                        {
                            "window_id": window_id,
                            "entity_id": "",
                            "column_key": column_key,
                            "claim_text": grounded_quote,
                            "quote_text": grounded_quote,
                            "relation_type": "SUPPORTS",
                            "support_score": 0.85,
                            "conflict_score": 0.02,
                        }
                        for column_key in schema_columns
                    ]
                }
            )
        if purpose == "research.verify.cell":
            return json.dumps(
                {
                    "status": "SUPPORTS",
                    "confidence": 0.82,
                    "reason": "evidence card directly grounds the candidate value",
                    "suggested_revision": "",
                }
            )
        if purpose == "research.verify.local":
            return json.dumps({"status": "PASS", "warnings": [], "recovery_actions": []})
        return ""


@pytest.fixture
def fake_default_llm(monkeypatch: pytest.MonkeyPatch) -> _FakeLlmClient:
    """显式启用 LLM 路径，并只 patch 默认 client builder。"""
    from app import llm_client as llm_client_module
    from app import runner as runner_module

    fake = _FakeLlmClient()
    monkeypatch.setattr(llm_client_module, "build_default_llm_client", lambda: fake)
    monkeypatch.setattr(runner_module, "build_default_llm_client", lambda: fake)

    return fake


@pytest.fixture
def fake_llm_client() -> _FakeLlmClient:
    """直接访问 FakeLlmClient 实例(用于断言 LLM 调用次数)。"""
    return _FakeLlmClient()
