"""在 Worker 容器内重放抽取前的原文截取：看某一列在每个已存档窗口里实际交给模型的是哪几段。

用法：docker exec -i <worker> python - < excerpt_replay.py，输入 JSON 由宿主机脚本写到 /tmp/excerpt_input.json：
{"column": {...列定义...}, "entity": "...", "question": "...", "windows": [{"window_id", "title", "url", "text"}], "keywords": [...]}
"""
import json

from app.extractor import _window_excerpt
from app.models import ResearchColumn, ResearchReadWindow
from app.planner import build_research_plan
from app.models import ResearchTaskInput

data = json.load(open("/tmp/excerpt_input.json", encoding="utf-8"))
column = ResearchColumn(**data["column"])
task_input = ResearchTaskInput.model_validate({
    "task_id": "replay", "workspace_id": "ws", "target_id": "run", "source_scope": [],
    "control_pack": {"pack_type": "research", "target_key": "DEFAULT", "task_neighborhood": "RESEARCH_DEFAULT"},
    "input_payload": {"question": data["question"], "profile_key": "DEFAULT"}})
plan = build_research_plan(task_input)
plan = plan.model_copy(update={
    "research_schema": plan.research_schema.model_copy(update={"columns": [column], "required_column_count": 1}),
    "stop_contract": {**plan.stop_contract, "deep_cell_entity_label": data["entity"]}})
for item in data["windows"]:
    window = ResearchReadWindow(window_id=item["window_id"], source_id=item["window_id"], source_title=item["title"],
                                query="q", read_focus="f", window_text=item["text"], url=item["url"],
                                hit_id=item["window_id"], retention_reason="replay", token_estimate=len(item["text"]) // 4)
    excerpt = _window_excerpt(plan, window)
    text = item["text"]
    hits = {keyword: (text.count(keyword), excerpt.count(keyword)) for keyword in data["keywords"]}
    print(f"{item['window_id']} {item['url'][:80]} text={len(text)} excerpt={len(excerpt)} keyword(text,excerpt)={hits}")
