from __future__ import annotations

import json
import hashlib
from pathlib import Path

from app.artifact_skill_catalog import CATALOG_DIGEST, list_artifact_skill_definitions, validate_published_catalog
from app.main import _to_public_input_schema
from app.registry import PRODUCTION_ACTIONS
from app.models import ArtifactTaskInput
from app.runner import run_artifact_task
import pytest


def test_worker_public_skill_catalog_should_match_cross_language_contract() -> None:
    contract_path = Path(__file__).resolve().parents[3] / "reference" / "artifact-skill-catalog-v1.json"
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    actual = {}
    for skill in list_artifact_skill_definitions():
        schema = _to_public_input_schema(skill.input_schema)
        actual[skill.skill_key] = {
            "properties": sorted(schema.get("properties", {}).keys()),
            "required": sorted(schema.get("required", [])),
        }
        language_schema = schema.get("properties", {}).get("language", {})
        assert language_schema.get("default") == contract["defaults"]["language"]

    expected = {
        skill_key: {
            "properties": sorted(definition["properties"]),
            "required": sorted(definition["required"]),
        }
        for skill_key, definition in contract["skills"].items()
    }
    assert expected.keys() <= actual.keys()
    assert {"knowledge_blog", "interview_qa", "video_learning_deck"} <= actual.keys()
    for skill_key, legacy in expected.items():
        assert set(legacy["properties"]) <= set(actual[skill_key]["properties"])
        assert actual[skill_key]["required"] == legacy["required"]
    assert "video_material_bundle_id" in actual["bilibili_course_note_pdf"]["properties"]


def test_published_skill_catalog_matches_both_runtime_copies_and_action_registry() -> None:
    root = Path(__file__).resolve().parents[3]
    source = (root / "reference" / "artifact-skill-catalog-v2.json").read_bytes()
    host = (root / "backend" / "src" / "main" / "resources" / "artifact-skill-catalog-v2.json").read_bytes()
    worker = (root / "workers" / "artifact-worker" / "app" / "artifact-skill-catalog-v2.json").read_bytes()
    assert source == host == worker
    assert CATALOG_DIGEST == hashlib.sha256(source).hexdigest()
    entries = json.loads(source)["skills"]
    assert len(entries) == len({entry["skill_key"] for entry in entries}) == 15
    for entry in entries:
        action = PRODUCTION_ACTIONS[entry["action_key"]]
        assert entry["graph_key"] == action.default_skill_graph_key
        assert entry["prompt_recipe_id"] == action.default_prompt_recipe_id
        assert entry["capability_allowlist"] == action.supported_capabilities


def test_worker_rejects_run_frozen_against_another_catalog_before_execution() -> None:
    task = ArtifactTaskInput.model_validate({
        "task_id": "catalog-mismatch", "workspace_id": "workspace", "target_id": "target",
        "input_snapshot_id": "snapshot", "catalog_digest": "0" * 64,
        "control_pack": {"pack_type": "artifact", "target_key": "study_guide",
                         "task_neighborhood": "ARTIFACT_SKILL_STUDY_GUIDE"},
        "input_payload": {"skill_key": "study_guide"},
    })
    with pytest.raises(ValueError, match="catalog digest"):
        run_artifact_task(task)


@pytest.mark.parametrize("mutation", [
    lambda catalog: catalog["skills"][0].update({"version": "999.0.0"}),
    lambda catalog: catalog["skills"][0].update({"arbitrary_script": "run.py"}),
    lambda catalog: catalog["skills"][0].update({"capability_allowlist": ["ARBITRARY_TOOL"]}),
    lambda catalog: catalog["skills"][0]["input_schema"]["properties"].update(
        {"dangerous": {"type": "object"}}),
])
def test_published_catalog_rejects_unknown_policy(mutation) -> None:
    root = Path(__file__).resolve().parents[3]
    catalog = json.loads((root / "reference" / "artifact-skill-catalog-v2.json").read_text(encoding="utf-8"))
    mutation(catalog)
    with pytest.raises(ValueError):
        validate_published_catalog(catalog)
