"""目录执行策略与按章节生成图的发布边界。"""

import json
from pathlib import Path

import pytest

from app.artifact_skill_catalog import (
    list_artifact_skill_definitions, resolve_generation_mode, validate_published_catalog,
)


def _catalog():
    root = Path(__file__).resolve().parents[3]
    return json.loads((root / "reference/artifact-skill-catalog-v2.json").read_text(encoding="utf-8"))


def _entry(catalog, skill):
    return next(entry for entry in catalog["skills"] if entry["skill_key"] == skill)


def _connect(graph, ids):
    graph["edges"] = [{"from_node": left, "to_node": right, "edge_type": "PREREQUISITE"}
        for left, right in zip(ids, ids[1:])]


def test_only_declared_long_artifacts_use_sectioned_execution_mode():
    catalog = _catalog()
    assert {entry["skill_key"] for entry in catalog["skills"] if entry["generation_mode"] == "SECTIONED"} \
        == {"study_guide", "quiz_pack", "wiki_page"}
    assert resolve_generation_mode("quiz_pack") == "SECTIONED"
    assert resolve_generation_mode("resume_highlight") == "SINGLE"
    assert resolve_generation_mode("reading_digest") == "SINGLE"
    assert resolve_generation_mode("unpublished_custom_skill", "CUSTOM_ACTION") == "SINGLE"


def test_old_publication_without_mode_keeps_single_generation():
    catalog = _catalog()
    _entry(catalog, "report_draft").pop("generation_mode")
    validate_published_catalog(catalog)


@pytest.mark.parametrize("mode", [None, True, [], "", "parallel", "SECTION_SYNTHESIS", "sectioned"])
def test_unknown_execution_modes_are_rejected(mode):
    catalog = _catalog()
    _entry(catalog, "report_draft")["generation_mode"] = mode
    with pytest.raises(ValueError, match="生成模式"):
        validate_published_catalog(catalog)


@pytest.mark.parametrize("skill,mode", [("study_guide", "SINGLE"), ("resume_highlight", "SECTIONED")])
def test_execution_mode_must_match_graph_steps(skill, mode):
    catalog = _catalog()
    _entry(catalog, skill)["generation_mode"] = mode
    with pytest.raises(ValueError):
        validate_published_catalog(catalog)


def test_sectioned_graph_requires_planner_even_if_remaining_graph_is_acyclic():
    catalog = _catalog()
    graph = _entry(catalog, "study_guide")["graph_definition"]
    graph["nodes"] = [node for node in graph["nodes"] if node["node_id"] != "outline"]
    _connect(graph, ["digest", "write", "consistency", "cite", "guard", "repair"])
    with pytest.raises(ValueError, match="必须包含唯一"):
        validate_published_catalog(catalog)


def test_sectioned_graph_checks_dependency_paths_instead_of_node_declaration_order():
    catalog = _catalog()
    graph = _entry(catalog, "study_guide")["graph_definition"]
    _connect(graph, ["digest", "outline", "write", "cite", "consistency", "guard", "repair"])
    with pytest.raises(ValueError, match="依赖顺序"):
        validate_published_catalog(catalog)


def test_public_skill_metadata_does_not_include_generation_strategy_or_node_contracts():
    metadata = json.dumps([skill.model_dump(mode="json") for skill in list_artifact_skill_definitions()])
    assert "generation_mode" not in metadata
    assert "graph_definition" not in metadata
    assert "citation_assignments" not in metadata
