"""第二批功能的最小离线回放，不访问 Provider。"""
import json
import sys
from pathlib import Path
import os
from types import SimpleNamespace
from app.requirement_definitions import compile_requirements, count_value
from app.requirement_coverage import distinct_answer_count
from app.narrative_report import NarrativeReportPolisher, _synthesis_chunks, _validated_cells, _cells_table

question = "请列出至少三项性能变化。"
definition = {"text": "性能变化", "question_quote": "至少三项性能变化", "dimension": "性能",
              "entities": [], "completion_mode": "DISTINCT_FACTS", "minimum_count": 3,
              "count_expression": "至少三项"}
assert compile_requirements([definition], question, [{"label": "性能"}], [{"label": "Atlas"}], required=True)[0]["minimum_count"] == 3
assert compile_requirements([], question, [], [], required=False) == []
assert count_value("at least three new features") == 3
try:
    compile_requirements([{**definition, "minimum_count": 2}], question, [{"label": "性能"}], [{"label": "Atlas"}], required=True)
except ValueError:
    pass
else:
    raise AssertionError("数量与用户原文不一致时必须拒绝")

class Different:
    def complete_json(self, purpose, payload):
        assert purpose == "research.claims.equivalent"
        return json.dumps({"equivalent": False})

assert distinct_answer_count([0, 1], [{"claim": "Index scans avoid a separate sort."}, {"claim": "Index scans avoid a separate sort."}], Different()) == 1
facts = [{"fact_id": "fact:" + str(i), "claim_text": f"功能 {i} 已发布。", "evidence_keys": [f"evidence:{i}"]} for i in range(40)]
cell = {"cell_key": "row:features", "candidate_value": facts[0]["claim_text"], "entity_label": "Atlas", "column_label": "功能",
        "evidence_keys": [fact["evidence_keys"][0] for fact in facts], "facts": facts,
        "evidence_quotes": [{"evidence_key": fact["evidence_keys"][0], "quote": fact["claim_text"] + "原文说明" * 100} for fact in facts]}
data = {"question": "Atlas 有哪些功能？", "cells": [cell], "requirement_coverage": {"requirements": []},
        "limitations": ["尚未充分回答：性能对比"], "narrative_llm_budget": 2}
chunks = _synthesis_chunks(_validated_cells(data))
assert len(chunks) > 1 and sum(len(item["facts"]) for chunk in chunks for item in chunk) == 40
class Unavailable:
    calls = 0
    def complete_json(self, purpose, payload):
        self.calls += 1
        assert len(payload["frozen_cells"]) <= 6
        raise RuntimeError("离线模拟成文不可用")
llm = Unavailable()
result = NarrativeReportPolisher(llm).polish(data)
assert llm.calls == result.llm_calls == 2
assert all(fact["claim_text"] in result.markdown and "[" + fact["evidence_keys"][0] + "]" in result.markdown for fact in facts)
assert data["limitations"][0] in result.narrative["limitations"]
if len(sys.argv) > 1:
    Path(sys.argv[1]).write_text(json.dumps({"input": data, "narrative": result.narrative, "markdown": result.markdown}, ensure_ascii=False), encoding="utf-8")
many = [{**cell, "cell_key": f"row{i}:features", "entity_label": f"Atlas{i}"} for i in range(6)]
table = _cells_table(many)
assert len(table["columns"]) == 2 and len(table["rows"]) == 6
print(json.dumps({"count_mismatch_rejected": True, "duplicate_facts_count": 1, "retained_report_facts": 40,
                  "narrative_calls": llm.calls, "preserved_comparison_entities": 6, "provider_calls": 0}))
