from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path

import pytest

import app.runner as runner_module


_TEST_RUNTIME = tempfile.TemporaryDirectory(prefix="noteweave-artifact-tests-")


def pytest_configure() -> None:
    root = Path(_TEST_RUNTIME.name)
    os.environ["NOTEWEAVE_ARTIFACT_REPOSITORY_FILE_PATH"] = str(root / "repository.json")
    os.environ["NOTEWEAVE_ARTIFACT_CUSTOMIZATION_FILE_PATH"] = str(root / "customizations.json")
    os.environ["NOTEWEAVE_ARTIFACT_RUNTIME_STATE_FILE_PATH"] = str(root / "runtime-state.json")
    os.environ["NOTEWEAVE_ARTIFACT_WAIT_QUEUE_FILE_PATH"] = str(root / "wait-queue.json")


def pytest_unconfigure() -> None:
    from app.acquisition_runtime import configure_acquisition_runtime_store
    from app.artifact_repository import configure_artifact_repository_backend
    from app.capability_wait_queue import configure_waiting_task_store
    from app.registry import configure_custom_artifact_config_store

    configure_acquisition_runtime_store(None)
    configure_waiting_task_store(None)
    configure_custom_artifact_config_store(None)
    configure_artifact_repository_backend("memory")
    _TEST_RUNTIME.cleanup()


class ContractArtifactTestProvider:
    provider_name = "contract-test-provider"
    model_name = "deterministic-artifact-test-model"

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, object]]] = []

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        self.calls.append((purpose, payload))
        if purpose == "artifact.compile_intent":
            return ""
        if not purpose.startswith("artifact.node."):
            return ""
        raw_inputs = payload.get("inputs")
        inputs = raw_inputs if isinstance(raw_inputs, dict) else {}
        raw_context = inputs.get("generation_context")
        context = raw_context if isinstance(raw_context, dict) else {}
        raw_passages = inputs.get("material_passages")
        passages = raw_passages if isinstance(raw_passages, list) else []
        if purpose == "artifact.node.resume_highlight_extractor":
            return json.dumps({"facts": [
                {"fact_id": f"fact-{index + 1}", "text": passage["content"],
                 "chunk_id": passage["chunk_id"], "quote": passage["content"]}
                for index, passage in enumerate(passages)
            ]}, ensure_ascii=False)
        if purpose == "artifact.node.resume_impact_normalizer":
            return json.dumps({"normalized_facts": [
                {**fact, "metrics": []} for fact in inputs["facts"]
            ]}, ensure_ascii=False)
        if purpose == "artifact.node.resume_bullet_writer":
            return self._resume_output(inputs, context, passages)
        if isinstance(inputs.get("sections"), list):
            return json.dumps({"sections": inputs["sections"]}, ensure_ascii=False)
        # 通用生成仅接收节点投影的上下文与检索片段，不读取完整任务或权限。
        payload = {
            **context,
            "sources": [{"title": passage["title"], "content": passage["content"]}
                        for passage in passages],
        }
        raw_sources = payload.get("sources")
        sources = raw_sources if isinstance(raw_sources, list) else []
        source_titles = [
            str(item.get("title", "")).strip()
            for item in sources
            if isinstance(item, dict) and str(item.get("title", "")).strip()
        ]
        source_contents = [
            str(item.get("content", "")).strip()
            for item in sources
            if isinstance(item, dict) and str(item.get("content", "")).strip()
        ]
        raw_outline = payload.get("outline")
        outline = (
            [str(value).strip() for value in raw_outline if str(value).strip()]
            if isinstance(raw_outline, list)
            else []
        ) or ["Generated artifact"]
        body_parts = [
            str(payload.get("user_requirement", "")).strip(),
            str(payload.get("goal", "")).strip(),
            *[
                str(value).strip()
                for key in ("focus_points", "required_phrases")
                for value in (
                    payload.get(key) if isinstance(payload.get(key), list) else []
                )
                if str(value).strip()
            ],
            *source_contents,
        ]
        grounded_body = "\n\n".join(dict.fromkeys(part for part in body_parts if part))
        if not grounded_body:
            grounded_body = "Test provider received no grounded source content."
        raw_recipe = payload.get("prompt_recipe")
        recipe = raw_recipe if isinstance(raw_recipe, dict) else {}
        raw_section_guidance = recipe.get("section_guidance")
        section_guidance = (
            raw_section_guidance if isinstance(raw_section_guidance, dict) else {}
        )
        raw_node_guidance = recipe.get("node_guidance")
        node_guidance = raw_node_guidance if isinstance(raw_node_guidance, dict) else {}
        generation_mode = str(recipe.get("generation_mode", "")).strip().upper()

        sections: list[dict[str, object]] = []
        for index, heading in enumerate(outline):
            body = grounded_body
            guidance = str(section_guidance.get(heading, "")).strip()
            if guidance:
                body = f"配方重点：{guidance}\n\n{body}"
            if index == 0:
                first_node_guidance = next(
                    (
                        str(value).strip()
                        for value in node_guidance.values()
                        if str(value).strip()
                    ),
                    "",
                )
                if first_node_guidance:
                    body = f"{body}\n\n节点侧重：{first_node_guidance}"
            if recipe.get("recipe_id") == "wiki_page_writer_v1" and index == len(outline) - 1:
                body = "\n".join([
                    *[f"- {title}：对照原文资料。" for title in source_titles],
                    "- 来源索引：保留资料引用以便复核。", "", body,
                ])
            if generation_mode == "QUIZ_SYNTHESIS" and index == 1:
                body = "\n".join(
                    [
                        "1. 根据资料概括第一个核心概念。",
                        "2. 比较资料中的关键关系。",
                        "3. 提出一个可验证的应用判断。",
                        "",
                        body,
                    ]
                )
            if generation_mode == "QUIZ_SYNTHESIS" and index == len(outline) - 1:
                body = "\n".join(
                    [
                        "- 基础：准确复述资料中的直接信息。",
                        "- 进阶：比较资料中的关键关系。",
                        "- 挑战：基于资料提出可验证的应用判断。",
                        "",
                        body,
                    ]
                )
            for forbidden in context.get("forbidden_patterns", []):
                body = body.replace(forbidden, "")
            sections.append(
                {
                    "heading": heading,
                    "body": body,
                    "source_refs": source_titles,
                }
            )
        return json.dumps(
            {"sections": sections},
            ensure_ascii=False,
        )

    def _resume_output(self, inputs, context, passages) -> str:
        facts = inputs["normalized_facts"]
        facts_by_id = {fact["fact_id"]: fact for fact in facts}
        passage_titles = {passage["chunk_id"]: passage["title"] for passage in passages}
        all_ids = list(facts_by_id)
        source_titles = list(dict.fromkeys(passage_titles[fact["chunk_id"]] for fact in facts))
        focus = [*context.get("focus_points", []), *context.get("required_phrases", [])]
        text = " ".join(dict.fromkeys([
            *(" ".join(fact["text"].splitlines()).strip() for fact in facts),
            *focus,
        ]))
        # 替身返回三个有原文引用的要点，生产执行器仍负责核对数字与事实标识。
        bullets = [{"text": text, "fact_ids": all_ids}]
        bullets.extend({"text": " ".join(facts[index % len(facts)]["text"].splitlines()).strip(),
                        "fact_ids": [facts[index % len(facts)]["fact_id"]]}
                       for index in range(2))
        for forbidden in context.get("forbidden_patterns", []):
            text = text.replace(forbidden, "")
            for bullet in bullets:
                bullet["text"] = bullet["text"].replace(forbidden, "")
        # Markdown 列表解析会去掉行首尾空白，声明的要点必须与正文逐字一致。
        text = text.strip()
        for bullet in bullets:
            bullet["text"] = bullet["text"].strip()
        sections = [
            {"heading": heading,
             "body": "\n".join("- " + bullet["text"] for bullet in bullets)
                     if heading == "简历亮点" else text,
             "source_refs": source_titles}
            for heading in context["outline"]
        ]
        return json.dumps({"sections": sections, "bullets": bullets}, ensure_ascii=False)


@pytest.fixture(autouse=True)
def configured_artifact_test_provider(monkeypatch: pytest.MonkeyPatch) -> None:
    provider = ContractArtifactTestProvider()
    monkeypatch.setattr(
        runner_module,
        "build_default_llm_client",
        lambda: provider,
    )
