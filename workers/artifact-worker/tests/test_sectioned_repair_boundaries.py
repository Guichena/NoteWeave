import json
from copy import deepcopy

import pytest

from app.repair_runtime import repair_failed_sections


class ParagraphRepairClient:
    def __init__(self, body):
        self.body = body
        self.calls = []

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, payload))
        return json.dumps({"sections": [{"heading": "正文", "body": self.body, "source_refs": []}]}, ensure_ascii=False)


def _state():
    return {"sections": [{"heading": "正文", "body": "稳定段落。\n\n重复段落。\n\n原有结尾。", "source_refs": []}],
        "verification": {"status": "FAIL", "failed_checks": ["重复段落"], "failed_sections": ["正文"],
            "paragraph_constraints": {"正文": [1]}, "repair_rounds": [], "sections": []},
        "section_materials": [], "material_passages": [], "generation_context": {"outline": ["正文"]}}


def test_consistency_only_repair_can_remove_problem_paragraph_without_changing_others():
    state = _state()
    client = ParagraphRepairClient("稳定段落。\n\n\n\n原有结尾。")
    verified = []

    def verify(candidate):
        verified.append(deepcopy(candidate["sections"]))
        return {"status": "PASS", "failed_checks": [], "llm_calls": 0, "execution_traces": []}

    result = repair_failed_sections(state, llm_client=client, verify=verify)
    assert result["repair_trace"]["status"] == "REPAIRED"
    assert verified[0][0]["body"] == client.body
    assert len(client.calls) == 1
    assert client.calls[0][1]["inputs"]["failed_paragraphs"] == [
        {"heading": "正文", "paragraph_index": 1, "text": "重复段落。"}]


@pytest.mark.parametrize("body", [
    "稳定段落。\n\n修改的问题段落。\n\n擅自更改结尾。",
    "稳定段落。\n \n修改的问题段落。\n\n原有结尾。",
])
def test_consistency_only_repair_rejects_changes_outside_marked_paragraph(body):
    state = _state()
    client = ParagraphRepairClient(body)

    def unexpected_verify(_candidate):
        raise AssertionError("越界改写不应进入重新校验")

    result = repair_failed_sections(state, llm_client=client, verify=unexpected_verify)
    assert result["repair_trace"]["status"] == "FAILED"
    assert result["sections"] == state["sections"]
    assert len(client.calls) == 4
    assert len(result["repair_trace"]["rounds"]) == 2
    assert all(not row["repaired_sections"] for row in result["repair_trace"]["rounds"])
