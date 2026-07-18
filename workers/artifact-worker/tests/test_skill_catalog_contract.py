from __future__ import annotations

import json
from pathlib import Path

from app.artifact_skill_catalog import list_artifact_skill_definitions
from app.main import _to_public_input_schema


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
    assert actual == expected
